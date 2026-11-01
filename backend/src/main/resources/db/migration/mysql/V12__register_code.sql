-- MiniDocs 生产环境 DDL（MySQL 8）：V12 · 注册邮箱验证码
--
-- 目的：注册页改为「填邮箱 → 收 6 位验证码 → 带码注册」，不再依赖邮件里的验证链接。
-- 验证码是一次性的短凭证，生命周期只有 10 分钟，因此单独一张表，不往 users 上挂列：
--   · 注册完成前还没有用户行，没有地方可以存；
--   · 一行一个邮箱（email 做主键），重发即覆盖，旧码自然作废；
--   · 注册成功后整行删除，不留下可被重放的凭据。
--
-- code_hash 存 SHA-256(email + ':' + code) 的十六进制，不存原文：
-- 6 位数字本身熵不高，真正的防线是「10 分钟 + 最多 5 次尝试 + 用完即删」；
-- 摘要只是让拖库的人不能直接把码读出来用。
--
-- attempts 记录已试错次数，达到上限即整行删除，避免离线穷举。
-- expires_at 与 created_at/updated_at 用 DATETIME，与其余表口径一致。
CREATE TABLE IF NOT EXISTS register_code (
    email      VARCHAR(128) NOT NULL COMMENT '收码邮箱（小写归一）',
    code_hash  VARCHAR(64)  NOT NULL COMMENT 'SHA-256(email:code) 十六进制',
    attempts   TINYINT      NOT NULL DEFAULT 0 COMMENT '已试错次数，达到上限即作废',
    expires_at DATETIME     NOT NULL COMMENT '验证码失效时间',
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    PRIMARY KEY (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
