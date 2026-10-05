-- MiniDocs 生产环境 DDL（MySQL 8）—— V4：知识库来源（本地目录 / 云端 Git 仓库）
--
-- 与 sqlite/V4__kb_git_source.sql 同批发布，语义完全一致，见该文件顶部的说明。

ALTER TABLE knowledge_base
    ADD COLUMN source_type          VARCHAR(16)  NOT NULL DEFAULT 'local',
    ADD COLUMN git_url              VARCHAR(512) NULL,
    ADD COLUMN git_branch           VARCHAR(128) NULL,
    ADD COLUMN git_username         VARCHAR(128) NULL,
    ADD COLUMN git_token            VARCHAR(1024) NULL,
    ADD COLUMN git_last_sync_at     DATETIME     NULL,
    ADD COLUMN git_last_sync_status VARCHAR(512) NULL,
    ADD COLUMN git_last_sync_ok     TINYINT(1)   NULL;
