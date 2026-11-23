-- KEYS: stock, users, activity status, request token
-- ARGV: requestNo, memberId, quantity, purchaseLimit, tokenTtlSeconds
local requestNo = ARGV[1]
local memberId = ARGV[2]
local quantity = tonumber(ARGV[3])
local purchaseLimit = tonumber(ARGV[4])
local tokenTtl = tonumber(ARGV[5])
local prior = redis.call('GET', KEYS[4])
if prior then
  if string.sub(prior, 1, 8) == 'RESERVED' then return tonumber(redis.call('GET', KEYS[1]) or '0') end
  return -4
end
if redis.call('GET', KEYS[3]) ~= 'ACTIVE' then return -1 end
if redis.call('EXISTS', KEYS[1]) == 0 then return -5 end
local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
if stock < quantity then return -2 end
local boughtOrReserved = tonumber(redis.call('HGET', KEYS[2], memberId) or '0')
if boughtOrReserved + quantity > purchaseLimit then return -3 end
redis.call('DECRBY', KEYS[1], quantity)
redis.call('HINCRBY', KEYS[2], memberId, quantity)
redis.call('SET', KEYS[4], 'RESERVED:' .. memberId .. ':' .. quantity, 'EX', tokenTtl)
return stock - quantity