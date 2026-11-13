-- MiniDocs 开发环境 DDL（SQLite 3）：V14 · 第三方应用访问令牌
--
-- 一行 = 一个签发给第三方应用的长期令牌，让它以账号身份读取官方接口数据
-- （GitHub Personal Access Token 的做法）。
--
-- 库里只存 SHA-256 摘要，不存原文：原文只在创建时回吐一次，丢了就重新签发一个。
-- 吊销用 revoked_at 标记而不是物理删除 —— 「谁在什么时候废掉了哪个令牌」本身也是留痕。

CREATE TABLE IF NOT EXISTS api_token (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL,
    name         TEXT    NOT NULL,
    token_hash   TEXT    NOT NULL,
    token_prefix TEXT    NOT NULL,
    scopes       TEXT    NOT NULL DEFAULT 'read',
    expires_at   TEXT,
    last_used_at TEXT,
    revoked_at   TEXT,
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_api_token_hash ON api_token (token_hash);
CREATE INDEX IF NOT EXISTS idx_api_token_user ON api_token (user_id);
