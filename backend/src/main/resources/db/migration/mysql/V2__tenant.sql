-- MiniDocs 生产环境 DDL（MySQL 8.0）—— V2：多租户地基（扩展阶段，只增不改）
-- 与 sqlite/V2__tenant.sql 语义一致；差异仅在于 MySQL 用 ADD COLUMN / UPDATE 即可，
-- 不需要 SQLite 的重建表。排序规则 utf8mb4_0900_ai_ci 本身大小写不敏感，
-- 等价于 SQLite 索引上的 COLLATE NOCASE。
--
-- 收紧 NOT NULL 与替换唯一索引放在 V3，必须与「会写新列的代码」同批发布。

-- ---------------------------------------------------------------- 新表

CREATE TABLE IF NOT EXISTS tenant (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    slug          VARCHAR(64)  NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    type          VARCHAR(16)  NOT NULL DEFAULT 'TEAM',
    owner_user_id BIGINT       NOT NULL,
    join_policy   VARCHAR(16)  NOT NULL DEFAULT 'request',
    description   VARCHAR(255) NULL,
    logo_url      VARCHAR(600) NULL,
    discoverable  TINYINT      NOT NULL DEFAULT 1,
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_slug (slug),
    KEY idx_tenant_type (type, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS tenant_member (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    role        VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    joined_from VARCHAR(16) NULL,
    invited_by  BIGINT      NULL,
    joined_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_member (tenant_id, user_id),
    KEY idx_tenant_member_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS tenant_join_request (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    message          VARCHAR(255) NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'pending',
    reviewer_user_id BIGINT       NULL,
    reviewed_at      DATETIME     NULL,
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_join_request (tenant_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS kb_member (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    kb_id      BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(16) NOT NULL DEFAULT 'EDITOR',
    granted_by BIGINT      NOT NULL,
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_member (kb_id, user_id),
    KEY idx_kb_member_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS doc_edit_lock (
    kb_id          BIGINT       NOT NULL,
    doc_path       VARCHAR(600) NOT NULL,
    holder_user_id BIGINT       NOT NULL,
    acquired_at    DATETIME     NOT NULL,
    expires_at     DATETIME     NOT NULL,
    PRIMARY KEY (kb_id, doc_path),
    KEY idx_doc_lock_holder (holder_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NULL,
    actor_user_id BIGINT      NOT NULL,
    action        VARCHAR(48) NOT NULL,
    kb_id         BIGINT      NULL,
    doc_path      VARCHAR(600) NULL,
    detail        JSON        NULL,
    created_at    DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_tenant (tenant_id, created_at),
    KEY idx_audit_actor (actor_user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 回填个人组织

-- 每个既有用户一个 PERSONAL 组织：不可被申请加入、不出现在发现列表。
-- 状态恒为 active：账号是否可用只在 users.status 表达一次，个人组织不重复表达，
-- 否则「审批通过」需要同时改两处，漏一条路径就变成用户被放行却看不见自己的库。
INSERT INTO tenant (slug, name, type, owner_user_id, join_policy, description, discoverable,
                    status, created_at, updated_at)
SELECT CONCAT('u-', LOWER(u.username)),
       COALESCE(NULLIF(TRIM(u.display_name), ''), u.username),
       'PERSONAL', u.id, 'invite_only', NULL, 0,
       'active', u.created_at, u.updated_at
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM tenant t WHERE t.owner_user_id = u.id AND t.type = 'PERSONAL');

-- 兜底：owner 已被删除的孤儿库也要有归属，否则回填丢行、目录变孤儿。此类组织恒为 disabled。
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

-- ---------------------------------------------------------------- knowledge_base 扩展列

ALTER TABLE knowledge_base
    ADD COLUMN tenant_id      BIGINT       NULL AFTER id,
    ADD COLUMN storage_key    VARCHAR(255) NULL AFTER slug,
    ADD COLUMN maintain_scope VARCHAR(12)  NULL AFTER visibility;

UPDATE knowledge_base k
    JOIN tenant t ON t.owner_user_id = k.owner_id AND t.type = 'PERSONAL'
SET k.tenant_id   = t.id,
    k.storage_key = CONCAT('o', t.id, '/', k.slug)
WHERE k.tenant_id IS NULL;

UPDATE knowledge_base SET maintain_scope = 'owner_only' WHERE maintain_scope IS NULL;

-- 本阶段不动唯一索引、不动 visibility 取值，理由见 sqlite/V2 末尾说明。
