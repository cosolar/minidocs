-- MiniDocs 开发环境 DDL（SQLite 3）：V10 · 库级配置入库
-- 详见 mysql/V10__kb_config.sql 的说明。
CREATE TABLE IF NOT EXISTS kb_config (
    storage_key TEXT PRIMARY KEY,
    config_json TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
