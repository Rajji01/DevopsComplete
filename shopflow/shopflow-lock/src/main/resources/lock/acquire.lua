-- Atomic acquire + fence token. The lock and its fence counter are separate keys, so they share a hash
-- SLOT via a {hashtag} (RedisLockStore keys them "lock:{<key>}" and "lock:{<key>}:fence") — required for
-- a multi-key script in Redis Cluster.
-- KEYS[1] = lock key   KEYS[2] = fence-counter key
-- ARGV[1] = token      ARGV[2] = ttl_ms
-- returns { acquired(0|1), fence_token }
local cur = redis.call('GET', KEYS[1])
if cur and cur ~= ARGV[1] then
  return { 0, -1 }                                  -- someone else holds it
end
redis.call('SET', KEYS[1], ARGV[1], 'PX', tonumber(ARGV[2]))
local fence = redis.call('INCR', KEYS[2])           -- strictly increases on every successful acquire
return { 1, fence }
