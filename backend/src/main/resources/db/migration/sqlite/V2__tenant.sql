-- MiniDocs 开发环境 DDL（SQLite 3）—— V2：多租户地基（扩展阶段，只增不改）
--
-- 本脚本承担两件事：
--   1. 新建组织 / 成员 / 入组申请 / 库维护名单 / 编辑锁 / 审计 六张表；
--   2. 为 knowledge_base 增加 tenant_id / storage_key / maintain_scope（一律可空），
--      并把既有知识库回填到「创建者的个人组织」。
--
-- 刻意不在本脚本收紧 NOT NULL、也不替换唯一索引 —— 那是 V3 的职责，且必须与「会写新列的代码」
-- 同批发布。曾把两者合在一步，结果 v1 的 KnowledgeBase 实体不含新字段，插入直接撞
-- NOT NULL 约束（PermissionBaselineTest 实测复现）。
--
-- 语义说明：owner_id 列名保留，但含义降级为「创建者」—— 权限判定一律经 AccessService，
-- 不得再从列名推断权限（规范 §1）。

-- ---------------------------------------------------------------- 新表

CREATE TABLE IF NOT EXISTS tenant (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    slug          TEXT    NOT NULL,
    name          TEXT    NOT NULL,
    type          TEXT    NOT NULL DEFAULT 'TEAM',
    owner_user_id INTEGER NOT NULL,
    join_policy   TEXT    NOT NULL DEFAULT 'request',
    description   TEXT,
    logo_url      TEXT,
    discoverable  INTEGER NOT NULL DEFAULT 1,
    status        TEXT    NOT NULL DEFAULT 'active',
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_slug ON tenant (slug COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_tenant_type ON tenant (type, status);

CREATE TABLE IF NOT EXISTS tenant_member (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id   INTEGER NOT NULL,
    user_id     INTEGER NOT NULL,
    role        TEXT    NOT NULL DEFAULT 'MEMBER',
    joined_from TEXT,
    invited_by  INTEGER,
    joined_at   TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_member ON tenant_member (tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_tenant_member_user ON tenant_member (user_id);

CREATE TABLE IF NOT EXISTS tenant_join_request (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id        INTEGER NOT NULL,
    user_id          INTEGER NOT NULL,
    message          TEXT,
    status           TEXT    NOT NULL DEFAULT 'pending',
    reviewer_user_id INTEGER,
    reviewed_at      TEXT,
    created_at       TEXT    NOT NULL,
    updated_at       TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_join_request ON tenant_join_request (tenant_id, user_id);

-- 库级维护名单。role 仅 EDITOR / VIEWER，且只追加不削减（规范 §2.3）
CREATE TABLE IF NOT EXISTS kb_member (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    kb_id      INTEGER NOT NULL,
    user_id    INTEGER NOT NULL,
    role       TEXT    NOT NULL DEFAULT 'EDITOR',
    granted_by INTEGER NOT NULL,
    created_at TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_member ON kb_member (kb_id, user_id);
CREATE INDEX IF NOT EXISTS idx_kb_member_user ON kb_member (user_id);

-- 分工式编辑锁。落 DB 而非进程内缓存：重启丢锁会直接导致两人同时编辑
CREATE TABLE IF NOT EXISTS doc_edit_lock (
    kb_id          INTEGER NOT NULL,
    doc_path       TEXT    NOT NULL,
    holder_user_id INTEGER NOT NULL,
    acquired_at    TEXT    NOT NULL,
    expires_at     TEXT    NOT NULL,
    PRIMARY KEY (kb_id, doc_path)
);

CREATE INDEX IF NOT EXISTS idx_doc_lock_holder ON doc_edit_lock (holder_user_id);

-- 审计只记动作不记正文（规范 §9：不做历史版本）
CREATE TABLE IF NOT EXISTS audit_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id     INTEGER,
    actor_user_id INTEGER NOT NULL,
    action        TEXT    NOT NULL,
    kb_id         INTEGER,
    doc_path      TEXT,
    detail        TEXT,
    created_at    TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_tenant ON audit_log (tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log (actor_user_id, created_at);

-- ---------------------------------------------------------------- 回填个人组织

-- 每个既有用户一个 PERSONAL 组织：不可被申请加入、不出现在发现列表。
-- 状态恒为 active：账号是否可用只在 users.status 表达一次，个人组织不重复表达，
-- 否则「审批通过」需要同时改两处，漏一条路径就变成用户被放行却看不见自己的库。
INSERT INTO tenant (slug, name, type, owner_user_id, join_policy, description, discoverable,
                    status, created_at, updated_at)
SELECT 'u-' || LOWER(u.username),
       COALESCE(NULLIF(TRIM(u.display_name), ''), u.username),
       'PERSONAL', u.id, 'invite_only', NULL, 0,
       'active', u.created_at, u.updated_at
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM tenant t WHERE t.owner_user_id = u.id AND t.type = 'PERSONAL');

-- 兜底：owner 已被删除的孤儿库（v1 无外键约束）也要有归属，否则回填会丢行、目录变孤儿
INSERT INTO tenant (slug, name, type, owner_user_id, join_policy, description, discoverable,
                    status, created_at, updated_at)
SELECT DISTINCT 'u-deleted-' || k.owner_id, '已注销用户', 'PERSONAL', k.owner_id, 'invite_only', NULL, 0,
                'disabled', k.created_at, k.updated_at
FROM knowledge_base k
WHERE k.owner_id NOT IN (SELECT id FROM users)
  AND NOT EXISTS (SELECT 1 FROM tenant t WHERE t.owner_user_id = k.owner_id AND t.type = 'PERSONAL');

-- 创建者必须成为自己个人组织的 OWNER，否则 M2 之后没人能看见自己的库
INSERT INTO tenant_member (tenant_id, user_id, role, joined_from, invited_by, joined_at)
SELECT t.id, t.owner_user_id, 'OWNER', 'bootstrap', NULL, t.created_at
FROM tenant t
WHERE t.type = 'PERSONAL'
  AND NOT EXISTS (SELECT 1 FROM tenant_member m WHERE m.tenant_id = t.id AND m.user_id = t.owner_user_id);

-- ---------------------------------------------------------------- knowledge_base 扩展列

ALTER TABLE knowledge_base ADD COLUMN tenant_id INTEGER;
ALTER TABLE knowledge_base ADD COLUMN storage_key TEXT;
ALTER TABLE knowledge_base ADD COLUMN maintain_scope TEXT DEFAULT 'owner_only';

UPDATE knowledge_base
SET tenant_id = (SELECT t.id FROM tenant t
                 WHERE t.owner_user_id = knowledge_base.owner_id AND t.type = 'PERSONAL'
                 LIMIT 1)
WHERE tenant_id IS NULL;

-- storage_key = 物理目录相对 vaults/ 的路径，形如 o{tenantId}/{slug}。
-- 引入它的原因：slug 降级为组织内唯一后，两个组织可以有同名 slug，磁盘必须靠 storage_key 区分；
-- 同时它也让「改 slug」不再等于「挪目录」。一经写入永不可变（规范 I4）。
UPDATE knowledge_base
SET storage_key = 'o' || tenant_id || '/' || slug
WHERE storage_key IS NULL AND tenant_id IS NOT NULL;

UPDATE knowledge_base
SET maintain_scope = 'owner_only'
WHERE maintain_scope IS NULL;

-- ---------------------------------------------------------------- 本阶段刻意不做的事

-- 平台超管不建特殊组织：其跨租户能力由 AccessService bypass 提供，与属于哪个组织无关（规范 §2.4）。
-- visibility 不做任何改写：v1 的 public / private 已落在 v2 值域内，'org' 是新增可选项，
-- 由用户在库设置里显式选择 —— 批量放大可见范围是最不该出现的迁移副作用。
