-- TOKEN BUCKET, atomic. HASH {tokens, ts}. Lazy refill: add (elapsed * rate) tokens, capped at capacity,
-- on each call -- no background timer, and an idle key costs nothing until touched. Allows bursts up to
-- capacity, then holds to the steady refill rate. The algorithm most API gateways use.
--
-- KEYS[1]=key  ARGV[1]=capacity  ARGV[2]=refill_per_sec  ARGV[3]=permits
-- returns { allowed, remaining_tokens, retry_after_ms }
local cap     = tonumber(ARGV[1])
local rate    = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local d      = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(d[1])
local ts     = tonumber(d[2])
if tokens == nil then tokens = cap; ts = now end                 -- first touch: bucket starts full

tokens = math.min(cap, tokens + math.max(0, now - ts) / 1000.0 * rate)   -- refill

local allowed = 0
local retry   = 0
if tokens >= permits then
  tokens = tokens - permits
  allowed = 1
else
  retry = math.ceil((permits - tokens) / rate * 1000)           -- ms until enough tokens drip in
end
redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', KEYS[1], math.ceil(cap / rate * 1000) + 1000)      -- ~time to refill from empty
return { allowed, math.floor(tokens), retry }
