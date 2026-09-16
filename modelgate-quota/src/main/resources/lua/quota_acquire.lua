-- Multi-dimensional quota check + consume, atomic in one round trip.
--
-- KEYS (per dimension i = 1..3): KEYS[(i-1)*2 + 1] = rpm zset, KEYS[(i-1)*2 + 2] = tpm counter
-- ARGV: 1 = now(ms)  2 = window(ms)  3..8 = rpm/tpm limits per dimension (dim i: 2i+1, 2i+2)
--       9 = requestId (sliding-window member)
--
-- limits < 0 mean "not limited"
-- returns {allowed, rejectedDimension, retryAfterMs, remainingRequests}

local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local member = ARGV[9]

for i = 1, 3 do
  local rpmLimit = tonumber(ARGV[2 * i + 1])
  local tpmLimit = tonumber(ARGV[2 * i + 2])
  local rpmKey = KEYS[(i - 1) * 2 + 1]
  local tpmKey = KEYS[(i - 1) * 2 + 2]

  if rpmLimit >= 0 then
    redis.call('ZREMRANGEBYSCORE', rpmKey, 0, now - window)
    if redis.call('ZCARD', rpmKey) >= rpmLimit then
      local retry = window
      local oldest = redis.call('ZRANGE', rpmKey, 0, 0, 'WITHSCORES')
      if oldest[2] ~= nil then
        retry = (tonumber(oldest[2]) + window) - now
      end
      if retry < 1 then
        retry = 1
      end
      return {0, i, math.floor(retry), 0}
    end
  end

  if tpmLimit >= 0 then
    local used = tonumber(redis.call('GET', tpmKey) or '0')
    if used >= tpmLimit then
      local ttl = redis.call('PTTL', tpmKey)
      if ttl < 1 then
        ttl = window
      end
      return {0, i, ttl, 0}
    end
  end
end

-- everything passed: consume the request slot of every limited dimension
local remaining = 0
for i = 1, 3 do
  local rpmLimit = tonumber(ARGV[2 * i + 1])
  if rpmLimit >= 0 then
    local rpmKey = KEYS[(i - 1) * 2 + 1]
    redis.call('ZADD', rpmKey, now, member)
    redis.call('PEXPIRE', rpmKey, window)
    if i == 1 then
      remaining = rpmLimit - redis.call('ZCARD', rpmKey)
    end
  end
end

return {1, -1, 0, remaining}
