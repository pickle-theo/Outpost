-- =============================================================================
-- Outpost 本地消息表 V1.0 DDL（MySQL 5.7+ / 8.0+，供 DBA 审核后执行）
--
-- 说明：
--   1. 默认表名 outpost_message = outpost.table-prefix(outpost_) + message；
--      若修改了 outpost.table-prefix，请同步替换本脚本中的表名与索引名。
--   2. 与 starter 内置资源 META-INF/outpost/schema-mysql.sql 完全一致，
--      后者仅在 outpost.auto-create-table=true 时用于幂等自动建表。
--   3. 生产环境建议 auto-create-table 保持 false，由 DBA 执行本脚本。
--   4. 字符集 utf8mb4，引擎 InnoDB；三个索引分别服务于到期待投递扫描、
--      按状态清理/统计、按业务键排查。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `outpost_message` (
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '消息主键',
  `biz_type`        varchar(64)     NOT NULL COMMENT '业务类型，对应 DeliveryHandler#getBizType',
  `biz_key`         varchar(256)    NOT NULL COMMENT '业务唯一标识，缓存场景为 namespace:cacheKey',
  `payload`         text            NOT NULL COMMENT '消息体，原样字符串，内核不解析',
  `message_type`    tinyint         NOT NULL DEFAULT '0' COMMENT '消息类型：0普通 1辅助消息(延迟双删第二次)',
  `status`          tinyint         NOT NULL DEFAULT '0' COMMENT '状态：0待投递 1处理中 2成功 3失败 4死信',
  `retry_count`     int             NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `max_retry`       int             NOT NULL DEFAULT '10' COMMENT '最大重试次数',
  `deliver_attempt` int             NOT NULL DEFAULT '0' COMMENT '投递尝试次数，每次被抢占+1，用于观测重复投递',
  `next_retry_time` datetime(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次可投递时间',
  `version`         int             NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `error_msg`       varchar(1024)   DEFAULT NULL COMMENT '最后一次失败原因(异常摘要)',
  `dead_reason`     tinyint         DEFAULT NULL COMMENT '死信原因：1重试耗尽 2不可恢复异常',
  `trace_id`        varchar(64)     DEFAULT NULL COMMENT '链路追踪ID',
  `create_time`     datetime(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time`     datetime(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_retry_time` (`status`,`next_retry_time`),
  KEY `idx_status_create_time` (`status`,`create_time`),
  KEY `idx_biz_type_key` (`biz_type`,`biz_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Outpost本地消息主表';
