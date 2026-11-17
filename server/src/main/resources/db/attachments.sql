-- 旧版本没有附件表时增量创建，保留已有附件记录。
CREATE TABLE IF NOT EXISTS it_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_type VARCHAR(24) NOT NULL,
    biz_no VARCHAR(32) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    stored_name VARCHAR(64) NOT NULL,
    content_type VARCHAR(100) DEFAULT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    uploader VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_att_stored(stored_name),
    KEY idx_att_biz(biz_type, biz_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
