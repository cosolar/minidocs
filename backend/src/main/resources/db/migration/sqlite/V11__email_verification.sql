-- MiniDocs 开发环境 DDL（SQLite 3）：V11 · 注册邮箱验证
-- 字段语义、DEFAULT 1 的理由见 mysql/V11__email_verification.sql。
--
-- SQLite 的 ADD COLUMN 一次只接受一列；时间列沿用 created_at 的 TEXT 口径。
ALTER TABLE users ADD COLUMN email_verified INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN verify_token TEXT;
ALTER TABLE users ADD COLUMN verify_expires_at TEXT;
CREATE INDEX IF NOT EXISTS idx_users_verify_token ON users (verify_token);
