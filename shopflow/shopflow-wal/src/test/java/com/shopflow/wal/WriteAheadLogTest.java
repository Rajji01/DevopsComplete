package com.shopflow.wal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteAheadLogTest {

    @TempDir
    Path dir;

    @Test
    void appendsAndReplaysInOrder() throws IOException {
        Path file = dir.resolve("wal.log");
        try (var wal = new WriteAheadLog(file)) {
            assertThat(wal.append("a".getBytes(UTF_8))).isEqualTo(0);
            assertThat(wal.append("bb".getBytes(UTF_8))).isEqualTo(1);
            assertThat(wal.append("ccc".getBytes(UTF_8))).isEqualTo(2);

            List<WriteAheadLog.Record> records = wal.replay();
            assertThat(records).hasSize(3);
            assertThat(records.get(0).lsn()).isEqualTo(0);
            assertThat(new String(records.get(1).payload(), UTF_8)).isEqualTo("bb");
            assertThat(new String(records.get(2).payload(), UTF_8)).isEqualTo("ccc");
        }
    }

    @Test
    void recordsSurviveAReopenAndLsnsContinue() throws IOException {
        Path file = dir.resolve("wal.log");
        try (var wal = new WriteAheadLog(file)) {
            wal.append("one".getBytes(UTF_8));
            wal.append("two".getBytes(UTF_8));
        }
        try (var reopened = new WriteAheadLog(file)) {
            assertThat(reopened.replay()).hasSize(2);
            assertThat(reopened.nextLsn()).isEqualTo(2);
            assertThat(reopened.append("three".getBytes(UTF_8))).isEqualTo(2); // continues, not restart
            assertThat(reopened.replay()).hasSize(3);
        }
    }

    @Test
    void aTornTailIsDiscardedOnRecovery() throws IOException {
        Path file = dir.resolve("wal.log");
        try (var wal = new WriteAheadLog(file)) {
            wal.append("good-1".getBytes(UTF_8));
            wal.append("good-2".getBytes(UTF_8));
        }
        // simulate a crash mid-append: a partial frame (fewer bytes than a full header) stuck on the end
        Files.write(file, new byte[]{1, 2, 3, 4, 5}, StandardOpenOption.APPEND);

        try (var recovered = new WriteAheadLog(file)) {
            assertThat(recovered.replay()).hasSize(2);          // torn tail ignored
            assertThat(recovered.nextLsn()).isEqualTo(2);
            // the garbage was truncated, so a fresh append lands cleanly and replays back
            recovered.append("good-3".getBytes(UTF_8));
            List<WriteAheadLog.Record> records = recovered.replay();
            assertThat(records).hasSize(3);
            assertThat(new String(records.get(2).payload(), UTF_8)).isEqualTo("good-3");
        }
    }

    @Test
    void crcCatchesAFlippedByteAndStopsAtThatRecord() throws IOException {
        Path file = dir.resolve("wal.log");
        try (var wal = new WriteAheadLog(file)) {
            wal.append("first".getBytes(UTF_8));
            wal.append("second".getBytes(UTF_8));
        }
        // flip a byte inside the FIRST record's payload region (after the 20-byte header) -> its CRC fails
        byte[] raw = Files.readAllBytes(file);
        raw[20] ^= 0xFF;
        Files.write(file, raw);

        try (var wal = new WriteAheadLog(file)) {
            // the scan stops at the first corrupt frame, so nothing after it is trusted either
            assertThat(wal.replay()).isEmpty();
        }
    }
}
