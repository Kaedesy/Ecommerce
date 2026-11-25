-- Reconciliation audit trail. Run once against the mall database before enabling the worker.
CREATE TABLE IF NOT EXISTS `oms_reconciliation_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `check_type` varchar(48) NOT NULL,
  `business_key` varchar(128) NOT NULL,
  `status` varchar(24) NOT NULL,
  `details` varchar(2000) NOT NULL,
  `occurrence_count` int NOT NULL DEFAULT 1,
  `first_seen` datetime NOT NULL,
  `last_seen` datetime NOT NULL,
  `resolved_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reconciliation_business` (`check_type`,`business_key`),
  KEY `idx_reconciliation_status_last_seen` (`status`,`last_seen`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单库存优惠券一致性核对记录';
