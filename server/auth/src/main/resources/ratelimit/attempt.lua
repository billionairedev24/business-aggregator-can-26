-- northline-auth rate limits (S-9): sliding-window attempt log + lockout with exponential backoff, for N subjects at
-- once, atomically. Time comes from the server (TIME), so app instances with skewed clocks agree.
--
-- KEYS (3 per subject, all with the same {action} hash tag): events (sorted set), lock (string with TTL), strikes
-- ARGV[1] = 'check' | 'record'; ARGV[2] = unique member for this attempt
-- ARGV per subject (5): window_ms, threshold, lockout_ms, max_lockout_ms, backoff_memory_ms
-- Returns {allowed (1|0), retry_after_ms, bitmask of subjects locked by this call}
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local n = #KEYS / 3

local wait = 0
for i = 0, n - 1 do
  local ttl = redis.call('PTTL', KEYS[i * 3 + 2])
  if ttl > wait then wait = ttl end
end
if wait > 0 then return {0, wait, 0} end
if ARGV[1] == 'check' then return {1, 0, 0} end

local locked_for = 0
local newly = 0
for i = 0, n - 1 do
  local a = 3 + i * 5
  local window = tonumber(ARGV[a])
  local threshold = tonumber(ARGV[a + 1])
  local lockout = tonumber(ARGV[a + 2])
  local max_lockout = tonumber(ARGV[a + 3])
  local memory = tonumber(ARGV[a + 4])
  local events = KEYS[i * 3 + 1]
  redis.call('ZREMRANGEBYSCORE', events, '-inf', now - window)
  redis.call('ZADD', events, now, ARGV[2])
  redis.call('PEXPIRE', events, window)
  if redis.call('ZCARD', events) >= threshold then
    local strikes = redis.call('INCR', KEYS[i * 3 + 3])
    redis.call('PEXPIRE', KEYS[i * 3 + 3], memory)
    local duration = math.floor(math.min(lockout * 2 ^ (strikes - 1), max_lockout))
    redis.call('SET', KEYS[i * 3 + 2], strikes, 'PX', duration)
    redis.call('DEL', events)
    newly = newly + 2 ^ i
    if duration > locked_for then locked_for = duration end
  end
end
if locked_for > 0 then return {0, locked_for, newly} end
return {1, 0, 0}
