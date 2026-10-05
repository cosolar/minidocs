-- MiniDocs 生产环境 DDL（MySQL 8）—— V7：站点配置
--
-- 说明见 sqlite/V7__site_config.sql。

CREATE TABLE IF NOT EXISTS site_config (
    id         TINYINT  NOT NULL,
    config     JSON     NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
