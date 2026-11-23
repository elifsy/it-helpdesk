CREATE TABLE IF NOT EXISTS it_purchase_catalog (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  name        VARCHAR(120) NOT NULL COMMENT '设备名称 / 型号',
  category    VARCHAR(64)  NOT NULL COMMENT '资产分类',
  spec        VARCHAR(255) DEFAULT NULL COMMENT '规格说明',
  remark      VARCHAR(255) DEFAULT NULL,
  icon        VARCHAR(32)  DEFAULT NULL COMMENT '卡片图标 key，空=按分类自动匹配',
  enabled     TINYINT      NOT NULL DEFAULT 1,
  sort        INT          NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_pc_enabled (enabled, sort)
) ENGINE=InnoDB COMMENT='可申请购买的设备目录';

CREATE TABLE IF NOT EXISTS it_purchase_request (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  no            VARCHAR(32)  NOT NULL COMMENT 'PR-yyyyMMdd-0001',
  catalog_id    BIGINT       DEFAULT NULL,
  item_name     VARCHAR(120) NOT NULL COMMENT '申请购买的设备（快照）',
  category      VARCHAR(64)  DEFAULT NULL,
  applicant     VARCHAR(64)  NOT NULL,
  dept          VARCHAR(64)  DEFAULT NULL,
  reason        VARCHAR(500) NOT NULL,
  expect_date   DATE         DEFAULT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                COMMENT 'PENDING/APPROVED/ORDERED/PENDING_PICKUP/COMPLETED/REJECTED/CANCELLED',
  approver      VARCHAR(64)  DEFAULT NULL,
  handled_at    DATETIME     DEFAULT NULL,
  erp_no        VARCHAR(64)  DEFAULT NULL COMMENT 'ERP 采购单号',
  ordered_at    DATETIME     DEFAULT NULL,
  asset_no      VARCHAR(32)  DEFAULT NULL COMMENT '入库后选定并配发的资产',
  handover_by   VARCHAR(64)  DEFAULT NULL,
  handover_at   DATETIME     DEFAULT NULL,
  remark        VARCHAR(255) DEFAULT NULL,
  ding_instance_id VARCHAR(64) DEFAULT NULL COMMENT '钉钉审批实例 id（轮询同步用）',
  ding_status   VARCHAR(24)  DEFAULT NULL COMMENT 'RUNNING/COMPLETED/TERMINATED',
  ding_synced_at DATETIME    DEFAULT NULL,
  ding_message  VARCHAR(255) DEFAULT NULL COMMENT '钉钉审批结果摘要',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pr_no (no),
  KEY idx_pr_status (status, created_at),
  KEY idx_pr_applicant (applicant, created_at),
  KEY idx_pr_asset (asset_no)
) ENGINE=InnoDB COMMENT='设备购入申请';

CREATE TABLE IF NOT EXISTS it_purchase_request_item (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  request_id   BIGINT       NOT NULL COMMENT '所属申请单',
  catalog_id   BIGINT       DEFAULT NULL,
  item_name    VARCHAR(120) NOT NULL COMMENT '设备名称（快照）',
  category     VARCHAR(64)  DEFAULT NULL,
  spec         VARCHAR(255) DEFAULT NULL,
  status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
               COMMENT 'PENDING 待处理 / ORDERED 已申请采购 / PENDING_PICKUP 待领取 / COMPLETED 已交付',
  erp_no       VARCHAR(64)  DEFAULT NULL COMMENT '本行采购对应的 ERP 单号',
  ordered_at   DATETIME     DEFAULT NULL,
  asset_no     VARCHAR(32)  DEFAULT NULL COMMENT '配发的具体设备',
  delivered_by VARCHAR(64)  DEFAULT NULL,
  delivered_at DATETIME     DEFAULT NULL,
  remark       VARCHAR(255) DEFAULT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_pri_request (request_id, id),
  KEY idx_pri_status (status),
  KEY idx_pri_asset (asset_no)
) ENGINE=InnoDB COMMENT='设备购入申请明细（一行一台设备，可单独流转）';
