-- Extend (renew) the TTL ONLY if we still own it — the watchdog's primitive. Renew well before expiry so a
-- long-running holder never loses the lock mid-work; stop renewing and it self-expires if the holder dies.
-- KEYS[1] = lock key   ARGV[1] = token   ARGV[2] = ttl_ms
-- returns 1 if extended, 0 if not ours
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))
end
return 0
