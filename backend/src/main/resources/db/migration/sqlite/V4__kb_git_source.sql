-- MiniDocs 开发环境 DDL（SQLite 3）—— V4：知识库来源（本地目录 / 云端 Git 仓库）
--
-- 新建知识库支持两种来源：local（服务器上的工作空间目录，默认）与 git（绑定线上仓库）。
--
-- 云端库依旧克隆到 vaults/{storage_key}，磁盘布局与本地库完全一致 —— 目录树、渲染、
-- 导入导出都只认 storage_key，不感知来源。因此这里只补元数据列，不动存储结构。
--
-- git_token 落的是 AES-GCM 密文（见 CryptoUtil），不存明文；git_last_sync_* 是最近一次
-- 同步的展示用快照，同步失败不该让整个知识库变成不可读，所以与主流程解耦。
--
-- SQLite 的 ADD COLUMN 一次只接受一列，故逐条写。

ALTER TABLE knowledge_base ADD COLUMN source_type TEXT NOT NULL DEFAULT 'local';
ALTER TABLE knowledge_base ADD COLUMN git_url TEXT;
ALTER TABLE knowledge_base ADD COLUMN git_branch TEXT;
ALTER TABLE knowledge_base ADD COLUMN git_username TEXT;
ALTER TABLE knowledge_base ADD COLUMN git_token TEXT;
ALTER TABLE knowledge_base ADD COLUMN git_last_sync_at TEXT;
ALTER TABLE knowledge_base ADD COLUMN git_last_sync_status TEXT;
ALTER TABLE knowledge_base ADD COLUMN git_last_sync_ok INTEGER;
