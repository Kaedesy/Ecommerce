-- Product-change Outbox. Run once against the mall database before deploying mall-admin.
CREATE TABLE IF NOT EXISTS `pms_product_outbox_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `attempts` int NOT NULL DEFAULT 0,
  `next_attempt_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `locked_until` datetime DEFAULT NULL,
  `last_error` varchar(1000) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_product_outbox_due` (`status`, `next_attempt_time`, `locked_until`, `id`),
  KEY `idx_product_outbox_product` (`product_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品变更可靠投递事件';
