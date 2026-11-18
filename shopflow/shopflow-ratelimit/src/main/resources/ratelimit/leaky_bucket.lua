-- LEAKY BUCKET (meter), atomic. HASH {level, ts}. The level drains at a fixed leak rate; a request adds
-- one unit; overflow past capacity -> reject. Unlike token bucket it does NOT allow sustained bursts: it
-- enforces a SMOOTH output rate, which is what you want in front of a fragile downstream that hates spikes.
--
-- KEYS[1]=key  ARGV[1]=capacity  ARGV[2]=leak_per_sec  ARGV[3]=permits
-- returns { allowed, remaining_room, retry_after_ms }
local cap     = tonumber(ARGV[1])
local leak    = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local d     = redis.call('HMGET', KEYS[1], 'level', 'ts')
local level = tonumber(d[1])
local ts    = tonumber(d[2])
if level == nil then level = 0; ts = now end                    -- first touch: empty bucket

level = math.max(0, level - math.max(0, now - ts) / 1000.0 * leak)       -- drain since last touch

local allowed = 0
local retry   = 0
if level + permits <= cap then
  level = level + permits
  allowed = 1
else
  retry = math.ceil((level + permits - cap) / leak * 1000)      -- ms until it drains enough room
end
redis.call('HSET', KEYS[1], 'level', level, 'ts', now)
redis.call('PEXPIRE', KEYS[1], math.ceil(cap / leak * 1000) + 1000)
return { allowed, math.floor(cap - level), retry }
