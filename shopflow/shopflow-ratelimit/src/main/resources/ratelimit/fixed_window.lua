-- FIXED WINDOW counter, atomic.
-- A whole EVAL runs with nothing else interleaved (Redis is single-threaded + script atomicity), so the
-- read-then-increment can never race across pods — which is the entire reason to push the logic into Lua
-- instead of doing GET then INCR from Java (two round trips = a race window = overshoot).
-- Clock is the SERVER clock (redis TIME), so every app pod shares one clock: no client-clock-skew bug.
-- One HASH key {w = window id, c = count} -> single-slot, safe in Redis Cluster.
--
-- KEYS[1] = limiter key     ARGV[1] = limit   ARGV[2] = window_ms   ARGV[3] = permits
-- returns  { allowed(0|1), remaining, retry_after_ms }
local limit     = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local permits   = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local window = math.floor(now / window_ms)

local d = redis.call('HMGET', KEYS[1], 'w', 'c')
local count = 0
if tonumber(d[1]) == window then count = tonumber(d[2]) end     -- same window? keep count, else it reset

local until_reset = (window + 1) * window_ms - now
if count + permits > limit then
  return { 0, limit - count, until_reset }
end

count = count + permits
redis.call('HSET', KEYS[1], 'w', window, 'c', count)
redis.call('PEXPIRE', KEYS[1], window_ms * 2)                   -- idle keys self-expire (no reaper needed)
return { 1, limit - count, until_reset }
