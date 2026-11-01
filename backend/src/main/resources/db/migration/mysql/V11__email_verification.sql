-- MiniDocs 生产环境 DDL（MySQL 8）：V11 · 注册邮箱验证
--
-- 目的：防乱注册。站点配好 SMTP 后，注册账号要先把邮箱验证通才能登录。
--
-- 三个字段各管一件事：
--   email_verified   闸门只看这一位。1 = 可以登录。
--   verify_token     当前那封验证邮件里的一次性令牌。<b>存的是 SHA-256 十六进制摘要</b>，
--                    不是原文：链接只在发信那一刻给用户，库里再泄一份也不能拿来重放。
--                    验证成功即置 NULL，令牌是一次性的。
--   verify_expires_at 令牌的失效时间（发信时写入，默认 24 小时）。过期后用户可重发，
--                    老链接自动作废 —— 没有这一列，一个泄漏的旧链接会永久有效。
--
-- DEFAULT 1 是有意的：历史用户迁移完即为「已验证」。
-- 否则上线这一次迁移会把所有存量账号挡在登录页外，而他们的邮箱根本没有对应的验证邮件可点。
-- 「老用户自愿补验证」走应用层的提示，不靠这一位的初值。
--
-- 只给 verify_token 建索引：验证接口手里只有令牌，需要反查它是谁的。
-- email_verified 不建 —— 选择性极低，且没有「列出所有未验证用户」这种查询。
ALTER TABLE users ADD COLUMN email_verified TINYINT(1) NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN verify_token VARCHAR(64) NULL;
ALTER TABLE users ADD COLUMN verify_expires_at DATETIME NULL;
CREATE INDEX idx_users_verify_token ON users (verify_token);
