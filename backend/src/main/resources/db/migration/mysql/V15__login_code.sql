-- V15 · 登录邮箱验证码
--
-- 目的：同一个账号密码连续输错 3 次后，改走「邮箱验证码登录」，验证通过即可进入平台，
-- 随后提醒重设密码。验证码是一次性短凭证，单独一张表，不往 users 上挂列：
--   · 一行一个邮箱（email 做主键），重发即覆盖，旧码自然作废；
--   · 登录成功后整行删除，不留下可被重放的凭据。
--
-- code_hash 存 SHA-256(email + ':' + code) 的十六进制，不存原文：
-- 6 位数字本身熵不高，真正的防线是「10 分钟 + 最多 5 次尝试 + 用完即删」。
--
-- attempts 记录已试错次数，达到上限即整行删除；expires_at 与其余表口径一致用 DATETIME。
--
-- reset_expires_at 非空表示「刚刚用验证码登录过，允许免原密码重设密码」的一次性许可：
-- Sa-Token 是 JWT 无状态模式，服务端会话不可用，只能把这条许可落在库里。
-- 重设成功、重新发码或用密码正常登录都会删掉这行。
CREATE TABLE IF NOT EXISTS login_code (
    email            VARCHAR(128) NOT NULL COMMENT '收码邮箱（小写归一）',
    code_hash        VARCHAR(64)  NOT NULL COMMENT 'SHA-256(email:code) 十六进制',
    attempts         TINYINT      NOT NULL DEFAULT 0 COMMENT '已试错次数，达到上限即作废',
    expires_at       DATETIME     NOT NULL COMMENT '验证码失效时间',
    reset_expires_at DATETIME     NULL COMMENT '免原密码重设密码的许可截止时间',
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
