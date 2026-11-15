-- SLIDING WINDOW COUNTER, atomic. Two counters per key (current + previous fixed window); estimate the
-- rolling count by weighting the previous window by how much of it still overlaps the sliding window.
-- O(1) memory, smooths the fixed-window boundary burst. Slightly approximate (assumes even spread).
--
-- KEYS[1]=key  ARGV[1]=limit  ARGV[2]=window_ms  ARGV[3]=permits
-- returns { allowed, remaining, retry_after_ms }
local limit     = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local permits   = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local window = math.floor(now / window_ms)

local d  = redis.call('HMGET', KEYS[1], 'cw', 'cc', 'pc')
local cw = tonumber(d[1])
local cc = tonumber(d[2]) or 0
local pc = tonumber(d[3]) or 0
if cw == nil or cw < window - 1 then          -- no state, or more than one window stale: both zero
  cc = 0; pc = 0
elseif cw == window - 1 then                  -- exactly one window old: slide current into previous
  pc = cc; cc = 0
end

local elapsed  = now - window * window_ms
local weight   = (window_ms - elapsed) / window_ms
local estimate = pc * weight + cc

if estimate + permits > limit then
  return { 0, math.floor(limit - estimate), window_ms - elapsed }
end
cc = cc + permits
redis.call('HSET', KEYS[1], 'cw', window, 'cc', cc, 'pc', pc)
redis.call('PEXPIRE', KEYS[1], window_ms * 2)
return { 1, math.floor(limit - estimate - permits), window_ms - elapsed }
