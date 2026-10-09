-- MiniDocs 开发环境 DDL（SQLite 3）：V9 · 分享页顶栏的开源仓库入口
-- 详见 mysql/V9__share_repo_link.sql 的说明。
ALTER TABLE shares ADD COLUMN repo_url TEXT;
ALTER TABLE shares ADD COLUMN show_repo INTEGER NOT NULL DEFAULT 0;