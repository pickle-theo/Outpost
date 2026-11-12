CREATE TABLE IF NOT EXISTS outpost_message (
  id              bigint       NOT NULL AUTO_INCREMENT,
  biz_type        varchar(64)  NOT NULL,
  biz_key         varchar(256) NOT NULL,
  payload         text         NOT NULL,
  message_type    tinyint      NOT NULL DEFAULT 0,
  status          tinyint      NOT NULL DEFAULT 0,
  retry_count     int          NOT NULL DEFAULT 0,
  max_retry       int          NOT NULL DEFAULT 10,
  deliver_attempt int          NOT NULL DEFAULT 0,
  next_retry_time timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  version         int          NOT NULL DEFAULT 0,
  error_msg       varchar(1024) DEFAULT NULL,
  dead_reason     tinyint      DEFAULT NULL,
  trace_id        varchar(64)  DEFAULT NULL,
  create_time     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_status_retry_time ON outpost_message (status, next_retry_time);
CREATE INDEX IF NOT EXISTS idx_status_create_time ON outpost_message (status, create_time);
CREATE INDEX IF NOT EXISTS idx_biz_type_key ON outpost_message (biz_type, biz_key);
