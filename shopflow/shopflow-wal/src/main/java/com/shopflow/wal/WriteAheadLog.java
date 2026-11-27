package com.shopflow.wal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * A write-ahead log: the durability primitive underneath databases, Kafka, and the transactional-outbox
 * pattern this project already uses. The rule it enforces is in the name — a change is APPENDED to the log
 * and flushed to disk BEFORE it is considered done, so after a crash you recover by replaying the log. The
 * append is sequential (fast) and {@code fsync}'d (durable); random-access state is rebuilt from the log.
 *
 * <h3>Frame + the torn-write boundary</h3>
 * Each record is framed {@code [len:int][lsn:long][crc32:long][payload]}, and the CRC covers {@code lsn}
 * + payload. On {@link #replay}, records are read in order and the scan STOPS at the first frame that is
 * truncated (a crash mid-write left a short tail) or whose CRC fails (a torn/partial write) — that point IS
 * the crash boundary. Everything before it was fully flushed and is safe to replay; nothing after it is
 * trusted. This "valid prefix, stop at the first bad record" is the whole correctness argument of a WAL:
 * you never half-apply the record that was being written when the power went out.
 *
 * <p>On open, {@link #recover} replays to find that boundary, TRUNCATES any torn tail, and positions the
 * channel to append after the last good record — so a crash during an append self-heals on restart. LSNs
 * (log sequence numbers) are assigned sequentially from 0 and continue across reopens.
 *
 * <p>Teaching scope: single segment, no checkpoint/compaction (a real WAL truncates after a checkpoint
 * folds the log into a snapshot — noted as the next step). {@code append}/{@code replay} are synchronized.
 */
public final class WriteAheadLog implements Closeable {

    /** One logged record: its sequence number and the bytes that were appended. */
    public record Record(long lsn, byte[] payload) {
    }

    private static final int HEADER = Integer.BYTES + Long.BYTES + Long.BYTES; // len + lsn + crc = 20
    private static final int MAX_PAYLOAD = 64 * 1024 * 1024;                   // guard against garbage lengths

    private final FileChannel channel;
    private long nextLsn;

    public WriteAheadLog(Path path) throws IOException {
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        recover();
    }

    /** Append {@code payload} durably (framed, CRC'd, fsync'd) and return its assigned LSN. */
    public synchronized long append(byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large: " + payload.length);
        }
        long lsn = nextLsn++;
        ByteBuffer frame = ByteBuffer.allocate(HEADER + payload.length);
        frame.putInt(payload.length).putLong(lsn).putLong(crc(lsn, payload)).put(payload).flip();
        channel.position(channel.size());
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
        channel.force(false); // fsync the bytes -> the record survives a crash
        return lsn;
    }

    /** Every durably-written record, in order, up to the crash boundary (first torn/corrupt frame). */
    public synchronized List<Record> replay() throws IOException {
        return scan().records;
    }

    private record ScanResult(List<Record> records, long validEnd) {
    }

    /** Read the valid prefix of records and the byte offset where the first bad/absent frame starts. */
    private ScanResult scan() throws IOException {
        List<Record> records = new ArrayList<>();
        long pos = 0;
        ByteBuffer header = ByteBuffer.allocate(HEADER);
        while (true) {
            header.clear();
            if (!readFully(pos, header)) {
                break; // not even a full header left -> torn tail
            }
            header.flip();
            int len = header.getInt();
            long lsn = header.getLong();
            long storedCrc = header.getLong();
            if (len < 0 || len > MAX_PAYLOAD) {
                break; // length is garbage -> treat as the boundary
            }
            ByteBuffer payload = ByteBuffer.allocate(len);
            if (!readFully(pos + HEADER, payload)) {
                break; // payload was cut short -> crash happened mid-record
            }
            byte[] bytes = payload.array();
            if (crc(lsn, bytes) != storedCrc) {
                break; // bytes don't match their CRC -> torn/partial write
            }
            records.add(new Record(lsn, bytes));
            pos += HEADER + len;
        }
        return new ScanResult(records, pos);
    }

    /** Crash recovery on open: drop any torn tail, continue LSNs after the last good record. */
    private void recover() throws IOException {
        ScanResult scan = scan();
        if (scan.validEnd < channel.size()) {
            channel.truncate(scan.validEnd); // discard the incomplete frame a crash left behind
        }
        channel.position(scan.validEnd);
        nextLsn = scan.records.isEmpty() ? 0 : scan.records.get(scan.records.size() - 1).lsn() + 1;
    }

    /** The next LSN that will be assigned (= number of records, since LSNs start at 0 and are contiguous). */
    public synchronized long nextLsn() {
        return nextLsn;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private static long crc(long lsn, byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(ByteBuffer.allocate(Long.BYTES).putLong(lsn).array());
        crc.update(payload);
        return crc.getValue();
    }

    /** Fill {@code buf} from {@code position}; false if the channel ends before it's full. */
    private boolean readFully(long position, ByteBuffer buf) throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, p);
            if (n < 0) {
                return false; // EOF before the buffer was filled
            }
            p += n;
        }
        return true;
    }
}
