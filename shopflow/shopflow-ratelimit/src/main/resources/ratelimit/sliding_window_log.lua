-- SLIDING WINDOW LOG, atomic. A sorted set of request timestamps (score = time): prune everything older
-- than the window, then the remaining cardinality IS the exact count in the last `window`. No boundary
-- burst, but O(limit) members per key -> the memory-hungry one; PEXPIRE keeps idle keys from lingering.
--
-- KEYS[1]=key  ARGV[1]=limit  ARGV[2]=window_ms  ARGV[3]=permits  ARGV[4]=unique_request_id
-- returns { allowed, remaining, retry_after_ms }
local limit     = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local permits   = tonumber(ARGV[3])
local rid       = ARGV[4]

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window_ms)      -- slide: drop entries older than the window
local count = redis.call('ZCARD', KEYS[1])

if count + permits > limit then
  -- the limit frees up when the OLDEST in-window entry exits the window
  local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
  local retry = 0
  if oldest[2] then retry = (tonumber(oldest[2]) + window_ms) - now end
  return { 0, limit - count, retry }
end

for i = 1, permits do
  redis.call('ZADD', KEYS[1], now, rid .. '-' .. i)              -- unique member/permit so none collide
end
redis.call('PEXPIRE', KEYS[1], window_ms + 1000)
return { 1, limit - count - permits, 0 }
