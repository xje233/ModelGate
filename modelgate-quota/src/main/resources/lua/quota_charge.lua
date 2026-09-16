-- Post-call token accounting for all three dimensions in one round trip.
--
-- KEYS: tpm counter of dimension i (1..3)
-- ARGV: 1 = tokens  2 = window(ms)  3..5 = tpm limit per dimension
--
-- Semantics: fixed window anchored at the first token written; the TTL is only set when the
-- counter is created, so the window does not slide on every charge.

local tokens = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
if tokens <= 0 then
  return 0
end

for i = 1, 3 do
  local limit = tonumber(ARGV[2 + i])
  if limit >= 0 then
    local key = KEYS[i]
    redis.call('INCRBY', key, tokens)
    if redis.call('PTTL', key) < 0 then
      redis.call('PEXPIRE', key, window)
    end
  end
end

return 1
