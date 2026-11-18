-- Release ONLY if we still own it — the check-and-delete is one atomic script so we can't delete a lock
-- that expired and was re-taken by someone else between our GET and our DEL.
-- KEYS[1] = lock key   ARGV[1] = token
-- returns 1 if we released it, 0 if it wasn't ours
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
