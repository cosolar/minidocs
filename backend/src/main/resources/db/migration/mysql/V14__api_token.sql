-- V14 · 第三方应用访问令牌
--
-- 一行 = 一个签发给第三方应用的长期令牌，让它以账号身份读取官方接口数据
-- （GitHub Personal Access Token 的做法）。
--
-- 库里只存 SHA-256 摘要，不存原文：原文只在创建时回吐一次，丢了就重新签发一个。
-- 吊销用 revoked_at 标记而不是物理删除 —— 「谁在什么时候废掉了哪个令牌」本身也是留痕。

CREATE TABLE IF NOT EXISTS api_token (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    name         VARCHAR(64) NOT NULL,
    token_hash   VARCHAR(64) NOT NULL,
    token_prefix VARCHAR(16) NOT NULL,
    scopes       VARCHAR(32) NOT NULL DEFAULT 'read',
    expires_at   DATETIME    NULL,
    last_used_at DATETIME    NULL,
    revoked_at   DATETIME    NULL,
    created_at   DATETIME    NOT NULL,
    updated_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_token_hash (token_hash),
    KEY idx_api_token_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
