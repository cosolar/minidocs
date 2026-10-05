-- MiniDocs 开发环境 DDL（SQLite 3）—— V3：多租户地基（收缩阶段）
--
-- 与「会写 tenant_id / storage_key / maintain_scope 的代码」同批发布。做四件事：
--   1. 兜底重跑 V2 的回填（若有行的 tenant_id 仍为空，说明有数据是在 V2 之后、代码之前写入的）；
--   2. knowledge_base 收紧为 NOT NULL；
--   3. slug 唯一性由全局降级为「组织内唯一」；
--   4. shares 唯一性脱离 owner_id。
--
-- SQLite 不支持 MODIFY / DROP CONSTRAINT，第 2、3 步走 create-copy-drop-rename 重建表。

-- ---------------------------------------------------------------- 1. 回填兜底

INSERT INTO tenant (slug, name, type, owner_user_id, join_policy, description, discoverable,
                    status, created_at, updated_at)
SELECT DISTINCT 'u-deleted-' || k.owner_id, '已注销用户', 'PERSONAL', k.owner_id, 'invite_only', NULL, 0,
                'disabled', k.created_at, k.updated_at
FROM knowledge_base k
WHERE k.owner_id NOT IN (SELECT id FROM users)
  AND NOT EXISTS (SELECT 1 FROM tenant t WHERE t.owner_user_id = k.owner_id AND t.type = 'PERSONAL');

INSERT INTO tenant_member (tenant_id, user_id, role, joined_from, invited_by, joined_at)
SELECT t.id, t.owner_user_id, 'OWNER', 'bootstrap', NULL, t.created_at
FROM tenant t
WHERE t.type = 'PERSONAL'
  AND NOT EXISTS (SELECT 1 FROM tenant_member m WHERE m.tenant_id = t.id AND m.user_id = t.owner_user_id);

UPDATE knowledge_base
SET tenant_id = (SELECT t.id FROM tenant t
                 WHERE t.owner_user_id = knowledge_base.owner_id AND t.type = 'PERSONAL'
                 LIMIT 1)
WHERE tenant_id IS NULL;

UPDATE knowledge_base
SET storage_key = 'o' || tenant_id || '/' || slug
WHERE storage_key IS NULL AND tenant_id IS NOT NULL;

UPDATE knowledge_base
SET maintain_scope = 'owner_only'
WHERE maintain_scope IS NULL OR maintain_scope = '';

-- ---------------------------------------------------------------- 2 & 3. 重建 knowledge_base

CREATE TABLE knowledge_base_v3 (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id      INTEGER NOT NULL,
    owner_id       INTEGER NOT NULL,
    name           TEXT    NOT NULL,
    slug           TEXT    NOT NULL,
    storage_key    TEXT    NOT NULL,
    description    TEXT,
    -- public 全平台公开 / org 仅本组织 / private 仅维护名单；默认保持 private，
    -- 新建库由用户显式选择可见性，不给默认放权
    visibility     TEXT    NOT NULL DEFAULT 'private',
    -- owner_only 仅创建者与组织管理员 / members 名单内 EDITOR / org_all 全组织
    maintain_scope TEXT    NOT NULL DEFAULT 'owner_only',
    cover_url      TEXT,
    tags           TEXT,
    doc_count      INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT    NOT NULL,
    updated_at     TEXT    NOT NULL
);

INSERT INTO knowledge_base_v3 (id, tenant_id, owner_id, name, slug, storage_key, description,
                               visibility, maintain_scope, cover_url, tags, doc_count,
                               created_at, updated_at)
SELECT id, tenant_id, owner_id, name, slug, storage_key, description,
       visibility, maintain_scope, cover_url, tags, doc_count, created_at, updated_at
FROM knowledge_base;

DROP INDEX IF EXISTS uk_kb_slug;
DROP INDEX IF EXISTS idx_kb_owner;
DROP INDEX IF EXISTS idx_kb_visibility;
DROP TABLE knowledge_base;
ALTER TABLE knowledge_base_v3 RENAME TO knowledge_base;

-- 两个组织可以有同名 slug；物理目录靠 storage_key 区分，故此处不再全局唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_tenant_slug ON knowledge_base (tenant_id, slug COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_kb_tenant ON knowledge_base (tenant_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_kb_owner ON knowledge_base (owner_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_kb_visibility ON knowledge_base (visibility, updated_at);

-- ---------------------------------------------------------------- 4. shares

-- 唯一性从 (owner_id, kb_id, target_key) 改为 (kb_id, target_key)：分享是团队资产，
-- 同一库同一目标只应有一条有效链接，与谁创建无关。
-- v1 下一库一 owner，故 (kb_id, target_key) 事实上已唯一，不会撞约束。
-- owner_id 列保留为「创建者」审计字段；撤销权由 SHARE_REVOKE 判定（规范 §2.4）。
-- 不冗余 tenant_id：库归属可由 kb_id JOIN 得到，多写一份只会与 knowledge_base 漂移。
DROP INDEX IF EXISTS uk_shares_target;
CREATE UNIQUE INDEX IF NOT EXISTS uk_shares_target ON shares (kb_id, target_key);
