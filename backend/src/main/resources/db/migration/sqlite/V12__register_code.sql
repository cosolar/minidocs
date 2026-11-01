-- MiniDocs 开发环境 DDL（SQLite 3）：V12 · 注册邮箱验证码
-- 字段语义与注意事项见 mysql/V12__register_code.sql。
--
-- 一行 = 一个邮箱当前待用的验证码。email 做主键：同一邮箱只保留最新一条，
-- 重发即覆盖，旧码随之一并失效。
CREATE TABLE IF NOT EXISTS register_code (
    email      TEXT    NOT NULL,
    code_hash  TEXT    NOT NULL,
    attempts   INTEGER NOT NULL DEFAULT 0,
    expires_at TEXT    NOT NULL,
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL,
    PRIMARY KEY (email)
);
