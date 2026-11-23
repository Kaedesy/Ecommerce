-- Mall Swarm 秒杀 V1 增量迁移
-- 执行前备份数据库；此脚本只扩展结构，不自动把旧活动配置成可售秒杀。

ALTER TABLE sms_flash_promotion_product_relation
  ADD COLUMN product_sku_id BIGINT NULL COMMENT '秒杀对应SKU' AFTER product_id,
  ADD COLUMN reserved_count INT NOT NULL DEFAULT 0 COMMENT '已建单未支付数量' AFTER flash_promotion_count,
  ADD COLUMN sold_count INT NOT NULL DEFAULT 0 COMMENT '已支付数量' AFTER reserved_count,
  ADD COLUMN stock_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PREPARING/READY/ACTIVE/ENDED/RECOVERY' AFTER sold_count,
  ADD COLUMN remaining_released TINYINT NOT NULL DEFAULT 0 COMMENT '活动剩余库存是否已释放' AFTER stock_status,
  ADD KEY idx_flash_relation_sku (product_sku_id),
  ADD UNIQUE KEY uk_promotion_session_sku (flash_promotion_id, flash_promotion_session_id, product_sku_id);

CREATE TABLE sms_flash_promotion_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_no VARCHAR(64) NOT NULL,
  member_id BIGINT NOT NULL,
  promotion_relation_id BIGINT NOT NULL,
  product_sku_id BIGINT NOT NULL,
  receive_address_id BIGINT NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  order_id BIGINT NULL,
  fail_reason VARCHAR(255) NULL,
  next_retry_time DATETIME NULL,
  lease_until DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_flash_request_no (request_no),
  UNIQUE KEY uk_flash_member_idem (member_id, idempotency_key),
  UNIQUE KEY uk_flash_order_id (order_id),
  KEY idx_flash_request_dispatch (status, next_retry_time, lease_until),
  KEY idx_flash_request_member (member_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀请求与可靠投递状态';

CREATE TABLE sms_flash_promotion_user_limit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  promotion_relation_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  reserved_count INT NOT NULL DEFAULT 0,
  bought_count INT NOT NULL DEFAULT 0,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_flash_relation_member (promotion_relation_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀会员限购计数';
