package com.shopflow.lock;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The distributed backend: lock state in Redis, each operation one atomic Lua script (see
 * {@code resources/lock/*.lua}). The lock key and its fence counter use a {@code {hashtag}} so they land in
 * the same cluster slot (a multi-key script needs that). This is a single-Redis lock; stack N of these
 * behind {@link RedLock} for the multi-node variant.
 */
public final class RedisLockStore implements LockStore {

    private final StringRedisTemplate redis;
    private final RedisScript<List> acquireScript;
    private final RedisScript<Long> releaseScript;
    private final RedisScript<Long> extendScript;

    public RedisLockStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.acquireScript = load("acquire.lua", List.class);
        this.releaseScript = load("release.lua", Long.class);
        this.extendScript = load("extend.lua", Long.class);
    }

    private static <T> RedisScript<T> load(String file, Class<T> type) {
        var s = new DefaultRedisScript<T>();
        s.setLocation(new ClassPathResource("lock/" + file));
        s.setResultType(type);
        return s;
    }

    // {hashtag} keeps the lock key and its fence counter in one slot (Redis Cluster safety)
    private static String lockKey(String key) {
        return "lock:{" + key + "}";
    }

    private static String fenceKey(String key) {
        return "lock:{" + key + "}:fence";
    }

    @Override
    @SuppressWarnings("unchecked")
    public AcquireResult acquire(String key, String token, long ttlMillis) {
        List<Long> r = (List<Long>) redis.execute(acquireScript,
                List.of(lockKey(key), fenceKey(key)), token, Long.toString(ttlMillis));
        return new AcquireResult(r.get(0) == 1L, r.get(1));
    }

    @Override
    public boolean release(String key, String token) {
        Long r = redis.execute(releaseScript, List.of(lockKey(key)), token);
        return r != null && r > 0;
    }

    @Override
    public boolean extend(String key, String token, long ttlMillis) {
        Long r = redis.execute(extendScript, List.of(lockKey(key)), token, Long.toString(ttlMillis));
        return r != null && r > 0;
    }
}
