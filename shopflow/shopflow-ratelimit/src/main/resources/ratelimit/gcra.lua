-- GCRA (Generic Cell Rate Algorithm), atomic. Stores ONE value: the Theoretical Arrival Time (tat, in ms)
-- -- no token count, no refill. Same burst-to-capacity shape as the token bucket, computed from the other
-- direction; this is what redis-cell does, and the natural fit for a single-key distributed limiter.
--
-- KEYS[1]=key  ARGV[1]=capacity  ARGV[2]=refill_per_sec  ARGV[3]=permits
-- returns { allowed, remaining, retry_after_ms }
local cap     = tonumber(ARGV[1])
local rate    = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local T     = 1000.0 / rate        -- emission interval: ms per permit
local limit = cap * T              -- a full bucket's worth of time
local inc   = permits * T

local tat = tonumber(redis.call('GET', KEYS[1]))
if tat == nil then tat = now end                 -- first touch: idle

local new_tat = math.max(tat, now) + inc
local ahead   = new_tat - now                    -- how far the projected arrival sits in the future

local allowed   = 0
local remaining = 0
local retry     = 0
if ahead <= limit then
  allowed = 1
  remaining = math.floor((limit - ahead) / T)
  redis.call('SET', KEYS[1], new_tat)
  redis.call('PEXPIRE', KEYS[1], math.ceil(limit) + 1000)   -- expire once a full bucket would have drained
else
  retry = math.ceil(ahead - limit)                          -- exact ms until this request would conform
end
return { allowed, remaining, retry }
