-- KEYS: activity stock, user counts, activity status, request token
-- ARGV: requestNo
local token = redis.call('GET', KEYS[4])
if not token then return -1 end
if token == 'RESTORED' then return 0 end
local memberId, quantity = string.match(token, '^RESERVED:([^:]+):(%d+)$')
if not memberId then return -2 end
local activityStatus = redis.call('GET', KEYS[3])
if activityStatus == 'ENDED' then
  redis.call('SET', KEYS[4], 'RESTORED', 'EX', 604800)
  return 1
end
if activityStatus ~= 'ACTIVE' and activityStatus ~= 'READY' then return -3 end
if redis.call('EXISTS', KEYS[1]) == 0 then return -3 end
local memberCount = tonumber(redis.call('HGET', KEYS[2], memberId) or '0')
if memberCount < tonumber(quantity) then return -3 end
redis.call('INCRBY', KEYS[1], tonumber(quantity))
local remaining = redis.call('HINCRBY', KEYS[2], memberId, -tonumber(quantity))
if remaining <= 0 then redis.call('HDEL', KEYS[2], memberId) end
redis.call('SET', KEYS[4], 'RESTORED', 'EX', 604800)
return 1