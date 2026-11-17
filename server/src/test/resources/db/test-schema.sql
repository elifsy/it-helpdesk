-- ============================================================
-- 内部 IT 报修平台 · MySQL 8.0 建表脚本
-- 字符集 utf8mb4，引擎 InnoDB
-- ============================================================




DROP TABLE IF EXISTS it_attachment;
DROP TABLE IF EXISTS it_notify;
DROP TABLE IF EXISTS it_ticket_event;
DROP TABLE IF EXISTS it_ticket_record;
DROP TABLE IF EXISTS it_ticket;
DROP TABLE IF EXISTS it_asset_request;
DROP TABLE IF EXISTS it_asset_history;
DROP TABLE IF EXISTS it_asset;
DROP TABLE IF EXISTS it_kb_article;
DROP TABLE IF EXISTS it_solution_code;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS sys_dept;

-- ------------------------------------------------------------
-- 组织与人员（由钉钉通讯录同步，不开放注册）
-- ------------------------------------------------------------
CREATE TABLE sys_dept (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  ding_dept_id VARCHAR(64)  DEFAULT NULL COMMENT '钉钉部门 id，通讯录同步用',
  name         VARCHAR(64)  NOT NULL,
  parent_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '父部门本地 id，0 为根',
  sort         INT          NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dept_ding (ding_dept_id),
  KEY idx_dept_parent (parent_id)
);

CREATE TABLE sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  ding_user_id  VARCHAR(64)  DEFAULT NULL COMMENT '钉钉 userid',
  name          VARCHAR(64)  NOT NULL,
  employee_no   VARCHAR(32)  DEFAULT NULL COMMENT '工号',
  dept_id       BIGINT       NOT NULL DEFAULT 0,
  dept_name     VARCHAR(64)  DEFAULT NULL,
  mobile        VARCHAR(32)  DEFAULT NULL,
  email         VARCHAR(128) DEFAULT NULL,
  role          VARCHAR(128) NOT NULL DEFAULT 'USER'
                COMMENT 'USER/IT/ADMIN/VIEWER，单角色',
  skill         VARCHAR(128) DEFAULT NULL COMMENT '工程师技能：网络,终端,系统',
  location      VARCHAR(64)  DEFAULT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_ding (ding_user_id),
  KEY idx_user_dept (dept_id),
  KEY idx_user_role (role)
);

-- ------------------------------------------------------------
-- IT 资产台账
-- ------------------------------------------------------------
CREATE TABLE it_asset (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  no            VARCHAR(32)  NOT NULL COMMENT '资产编号，二维码内容载体',
  name          VARCHAR(128) NOT NULL,
  model         VARCHAR(128) DEFAULT NULL COMMENT '设备型号',
  category      VARCHAR(32)  NOT NULL COMMENT '终端设备/打印复印/网络设备/服务器/会议室/软件许可',
  sn            VARCHAR(64)  DEFAULT NULL,
  user_name     VARCHAR(64)  DEFAULT NULL COMMENT '使用人',
  location      VARCHAR(64)  DEFAULT NULL,
  buy_date      DATE         DEFAULT NULL,
  warranty      VARCHAR(32)  DEFAULT NULL COMMENT '厂商保修/自保/已过保',
  warranty_end  DATE         DEFAULT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT '闲置'
                COMMENT '在用/闲置/借出中/维修中/待报废/已报废/待入库',
  borrower      VARCHAR(64)  DEFAULT NULL,
  borrow_due    DATE         DEFAULT NULL,
  remark        VARCHAR(255) DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_asset_no (no),
  KEY idx_asset_status (status),
  KEY idx_asset_user (user_name)
);

CREATE TABLE it_asset_history (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  asset_no    VARCHAR(32)  NOT NULL,
  action      VARCHAR(32)  NOT NULL COMMENT '领用/借用/归还/更换/维修/调拨',
  detail      VARCHAR(255) DEFAULT NULL,
  operator    VARCHAR(64)  DEFAULT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ah_asset (asset_no, created_at)
);

-- ------------------------------------------------------------
-- 资产申请（领用 / 借用 / 更换 / 归还）
-- ------------------------------------------------------------
CREATE TABLE it_asset_request (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  no             VARCHAR(32)  NOT NULL COMMENT 'AS-yyyyMMdd-0001',
  type           VARCHAR(16)  NOT NULL COMMENT 'RECEIVE/BORROW/REPLACE/RETURN',
  asset_no       VARCHAR(32)  NOT NULL,
  applicant      VARCHAR(64)  NOT NULL,
  dept           VARCHAR(64)  DEFAULT NULL,
  reason         VARCHAR(500) NOT NULL,
  expect_return  DATE         DEFAULT NULL,
  status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                 COMMENT 'PENDING/APPROVED/REJECTED/COMPLETED/CANCELLED',
  approver       VARCHAR(64)  DEFAULT NULL,
  handled_at     DATETIME     DEFAULT NULL,
  new_asset_no   VARCHAR(32)  DEFAULT NULL,
  remark         VARCHAR(255) DEFAULT NULL,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ar_no (no),
  KEY idx_ar_status (status, created_at),
  KEY idx_ar_applicant (applicant, created_at),
  KEY idx_ar_asset (asset_no)
);

-- ------------------------------------------------------------
-- 工单
-- ------------------------------------------------------------
CREATE TABLE it_ticket (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  no             VARCHAR(32)  NOT NULL COMMENT 'IT-yyyyMMdd-0001',
  title          VARCHAR(200) NOT NULL,
  description    TEXT         NOT NULL,
  type           VARCHAR(24)  NOT NULL DEFAULT 'INCIDENT'
                 COMMENT 'INCIDENT/SERVICE_REQUEST/QUESTION',
  cat1           VARCHAR(32)  NOT NULL,
  cat2           VARCHAR(32)  DEFAULT NULL,
  category_note  VARCHAR(200) DEFAULT NULL,
  impact         VARCHAR(16)  NOT NULL DEFAULT 'SELF' COMMENT 'SELF/DEPT/FLOOR/ALL',
  work_impact    VARCHAR(16)  NOT NULL DEFAULT 'SLOW' COMMENT 'FINE/SLOW/BLOCKED',
  priority       VARCHAR(4)   NOT NULL DEFAULT 'P3' COMMENT 'P1-P4',
  status         VARCHAR(24)  NOT NULL DEFAULT 'NEW'
                 COMMENT 'NEW/ACCEPTED/IN_PROGRESS/PENDING_USER/PENDING_VENDOR/PENDING_CONFIRM/CLOSED/REJECTED/CANCELLED',
  asset_no       VARCHAR(32)  DEFAULT NULL,
  creator_id     BIGINT       NOT NULL,
  creator_name   VARCHAR(64)  NOT NULL,
  dept_name      VARCHAR(64)  DEFAULT NULL,
  location       VARCHAR(64)  DEFAULT NULL,
  assignee_id    BIGINT       DEFAULT NULL,
  assignee_name  VARCHAR(64)  DEFAULT NULL,
  source         VARCHAR(24)  DEFAULT '钉钉工作台' COMMENT '钉钉工作台/扫码/代客报修/电话补录',
  kb_hit         VARCHAR(255) DEFAULT NULL COMMENT '提报前命中的知识条目 id，逗号分隔',
  tried_kb       TINYINT      NOT NULL DEFAULT 0,
  solution_code  VARCHAR(64)  DEFAULT NULL,
  solution_note  VARCHAR(500) DEFAULT NULL,
  rating         TINYINT      DEFAULT NULL,
  rating_tags    VARCHAR(255) DEFAULT NULL,
  paused_min     INT          NOT NULL DEFAULT 0 COMMENT '挂起/待第三方停表分钟数',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  accepted_at    DATETIME     DEFAULT NULL,
  assigned_at    DATETIME     DEFAULT NULL,
  due_at         DATETIME     DEFAULT NULL COMMENT '解决时限（响应时限另算）',
  finished_at    DATETIME     DEFAULT NULL,
  closed_at      DATETIME     DEFAULT NULL,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ticket_no (no),
  KEY idx_ticket_status (status, created_at),
  KEY idx_ticket_creator (creator_id, created_at),
  KEY idx_ticket_assignee (assignee_id, status),
  KEY idx_ticket_priority (priority, status),
  KEY idx_ticket_cat (cat1, created_at)
);

CREATE TABLE it_ticket_event (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  ticket_no   VARCHAR(32)  NOT NULL,
  from_status VARCHAR(24)  DEFAULT NULL,
  to_status   VARCHAR(24)  DEFAULT NULL,
  action      VARCHAR(32)  NOT NULL COMMENT 'CREATE/ACCEPT/DISPATCH/RECORD/FINISH/CONFIRM/RATE/REJECT/CANCEL/PAUSE/RESUME',
  operator    VARCHAR(64)  DEFAULT NULL,
  remark      VARCHAR(500) DEFAULT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_te_ticket (ticket_no, created_at)
);

CREATE TABLE it_ticket_record (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  ticket_no   VARCHAR(32)  NOT NULL,
  kind        VARCHAR(24)  NOT NULL DEFAULT 'REMOTE'
              COMMENT 'REMOTE/ONSITE/PHONE/REPAIR/VENDOR/REPLACE/KB',
  content     VARCHAR(1000) NOT NULL,
  cost_min    INT          NOT NULL DEFAULT 0,
  operator    VARCHAR(64)  DEFAULT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tr_ticket (ticket_no, created_at)
);

-- ------------------------------------------------------------
-- 附件（报修截图、处理照片、资产照片）
-- ------------------------------------------------------------
CREATE TABLE it_attachment (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  biz_type     VARCHAR(24)  NOT NULL COMMENT 'TICKET/ASSET/REQUEST',
  biz_no       VARCHAR(32)  NOT NULL COMMENT '业务单号，如 IT-20261109-0001',
  file_name    VARCHAR(255) NOT NULL COMMENT '原始文件名，仅用于展示',
  stored_name  VARCHAR(64)  NOT NULL COMMENT 'UUID 文件名，磁盘与访问地址均用它',
  content_type VARCHAR(100) DEFAULT NULL,
  file_size    BIGINT       NOT NULL DEFAULT 0,
  uploader     VARCHAR(64)  NOT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_att_stored (stored_name),
  KEY idx_att_biz (biz_type, biz_no, created_at)
);

-- ------------------------------------------------------------
-- 知识库与字典
-- ------------------------------------------------------------
CREATE TABLE it_kb_article (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  title       VARCHAR(200) NOT NULL,
  category    VARCHAR(32)  NOT NULL,
  summary     VARCHAR(500) DEFAULT NULL,
  content     MEDIUMTEXT   NOT NULL,
  views       INT          NOT NULL DEFAULT 0,
  helpful     INT          NOT NULL DEFAULT 0,
  not_helpful INT          NOT NULL DEFAULT 0,
  status      VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
  source_ticket_no VARCHAR(32) DEFAULT NULL,
  revision    INT          NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_kb_cat (category, status),
  UNIQUE KEY uk_kb_source_ticket (source_ticket_no)
);

CREATE TABLE it_solution_code (
  id        BIGINT      NOT NULL AUTO_INCREMENT,
  category  VARCHAR(32) NOT NULL,
  code      VARCHAR(64) NOT NULL,
  name      VARCHAR(64) NOT NULL,
  sort      INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sc (category, code)
);

CREATE TABLE it_notify (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  to_user     VARCHAR(64)  NOT NULL,
  title       VARCHAR(120) NOT NULL,
  content     VARCHAR(500) DEFAULT NULL,
  type        VARCHAR(24)  NOT NULL DEFAULT 'TICKET' COMMENT 'TICKET/ASSET/SLA/SYSTEM',
  ref_no      VARCHAR(32)  DEFAULT NULL,
  is_read     TINYINT      NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_nt_user (to_user, is_read, created_at)
);
