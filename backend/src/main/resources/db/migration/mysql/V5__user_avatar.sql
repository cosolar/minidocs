-- MiniDocs 生产环境 DDL（MySQL 8）—— V5：用户头像
--
-- 说明见 sqlite/V5__user_avatar.sql。

ALTER TABLE users ADD COLUMN avatar_url VARCHAR(255) NULL;
