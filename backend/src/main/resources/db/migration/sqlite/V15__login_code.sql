-- MiniDocs 开发环境 DDL（SQLite 3）：V15 · 登录邮箱验证码
-- 字段语义与注意事项见 mysql/V15__login_code.sql。
--
-- 一行 = 一个邮箱当前待用的登录验证码。email 做主键：同一邮箱只保留最新一条，
-- 重发即覆盖，旧码随之一并失效；校验通过后整行删除。
--
-- reset_expires_at 非空表示「刚刚用验证码登录过，允许免原密码重设密码」，
-- 在指定时间前有效。Sa-Token 是 JWT 无状态模式，拿不到服务端会话，
-- 所以这条一次性许可只能落在库里；重设成功或重新用密码登录即删除。
CREATE TABLE IF NOT EXISTS login_code (
    email            TEXT    NOT NULL,
    code_hash        TEXT    NOT NULL,
    attempts         INTEGER NOT NULL DEFAULT 0,
    expires_at       TEXT    NOT NULL,
    reset_expires_at TEXT,
    created_at       TEXT    NOT NULL,
    updated_at       TEXT    NOT NULL,
    PRIMARY KEY (email)
);
