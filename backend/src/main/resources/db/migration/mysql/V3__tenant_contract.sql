-- MiniDocs 生产环境 DDL（MySQL 8.0）—— V3：多租户地基（收缩阶段）
-- 与 sqlite/V3__tenant_contract.sql 语义一致，必须与「会写新列的代码」同批发布。
-- 做四件事：回填兜底 → 收紧 NOT NULL → slug 降级为组织内唯一 → shares 唯一性脱离 owner_id。

-- ---------------------------------------------------------------- 1. 回填兜底

INSERT INTO tenant (slug, name, type, owner_user_id, join_policy, description, discoverable,
                    status, created_at, updated_at)
SELECT DISTINCT CONCAT('u-deleted-', k.owner_id), '已注销用户', 'PERSONAL', k.owner_id, 'invite_only', NULL, 0,
                'disabled', k.created_at, k.updated_at
FROM knowledge_base k
WHERE k.owner_id NOT IN (SELECT id FROM users)
  AND NOT EXISTS (SELECT 1 FROM tenant t WHERE t.owner_user_id = k.owner_id AND t.type = 'PERSONAL');

INSERT INTO tenant_member (tenant_id, user_id, role, joined_from, invited_by, joined_at)
SELECT t.id, t.owner_user_id, 'OWNER', 'bootstrap', NULL, t.created_at
FROM tenant t
WHERE t.type = 'PERSONAL'
  AND NOT EXISTS (SELECT 1 FROM tenant_member m WHERE m.tenant_id = t.id AND m.user_id = t.owner_user_id);

UPDATE knowledge_base k
    JOIN tenant t ON t.owner_user_id = k.owner_id AND t.type = 'PERSONAL'
SET k.tenant_id = t.id
WHERE k.tenant_id IS NULL;

UPDATE knowledge_base
SET storage_key = CONCAT('o', tenant_id, '/', slug)
WHERE storage_key IS NULL AND tenant_id IS NOT NULL;

UPDATE knowledge_base
SET maintain_scope = 'owner_only'
WHERE maintain_scope IS NULL OR maintain_scope = '';

-- ---------------------------------------------------------------- 2. 收紧 NOT NULL
-- 默认值保持 private：新建库由用户显式选择可见性，不给默认放权

ALTER TABLE knowledge_base
    MODIFY COLUMN tenant_id      BIGINT       NOT NULL,
    MODIFY COLUMN storage_key    VARCHAR(255) NOT NULL,
    MODIFY COLUMN visibility     VARCHAR(8)   NOT NULL DEFAULT 'private',
    MODIFY COLUMN maintain_scope VARCHAR(12)  NOT NULL DEFAULT 'owner_only';

-- ---------------------------------------------------------------- 3. slug 降级为组织内唯一

ALTER TABLE knowledge_base
    DROP INDEX uk_kb_slug,
    ADD UNIQUE KEY uk_kb_tenant_slug (tenant_id, slug),
    ADD KEY idx_kb_tenant (tenant_id, updated_at);

-- ---------------------------------------------------------------- 4. shares

ALTER TABLE shares DROP INDEX uk_shares_target;
ALTER TABLE shares ADD UNIQUE KEY uk_shares_target (kb_id, target_key);
