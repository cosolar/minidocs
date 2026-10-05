-- MiniDocs 开发环境 DDL（SQLite 3）—— V7：站点配置
--
-- 站点名称 / 副标题 / Logo 属于整个部署，既不属于某个组织也不属于某个人，
-- 因此单独一张只有一行的表：id 恒为 1，config 存 JSON。
--
-- 用 JSON 一列而不是逐字段建列，是为了后续加「页脚、备案号」这类站点级字段时不必每次再发迁移；
-- 空表（从未配置过）由服务层回落到内置默认品牌，不在此处插初始行。

CREATE TABLE IF NOT EXISTS site_config (
    id         INTEGER NOT NULL,
    config     TEXT    NOT NULL,
    updated_at TEXT    NOT NULL,
    PRIMARY KEY (id)
);
