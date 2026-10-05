-- MiniDocs 开发环境 DDL（SQLite 3，要求 >= 3.31 以支持生成列）
-- 时间统一使用 ISO8601 文本（yyyy-MM-ddTHH:mm:ss），由 TypeHandler 负责互转

CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL,
    password_hash TEXT    NOT NULL,
    display_name  TEXT,
    email         TEXT,
    role          TEXT    NOT NULL DEFAULT 'USER',
    status        TEXT    NOT NULL DEFAULT 'active',
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL
);

-- 应用层统一小写存储；索引加 COLLATE NOCASE 对齐 MySQL 的默认不敏感排序规则
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username ON users (username COLLATE NOCASE);
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email ON users (email COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS knowledge_base (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id    INTEGER NOT NULL,
    name        TEXT    NOT NULL,
    slug        TEXT    NOT NULL,
    description TEXT,
    visibility  TEXT    NOT NULL DEFAULT 'private',
    cover_url   TEXT,
    tags        TEXT,
    doc_count   INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_slug ON knowledge_base (slug COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_kb_owner ON knowledge_base (owner_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_kb_visibility ON knowledge_base (visibility, updated_at);

CREATE TABLE IF NOT EXISTS shares (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    token         TEXT    NOT NULL,
    owner_id      INTEGER NOT NULL,
    kb_id         INTEGER NOT NULL,
    doc_path      TEXT,
    target_key    TEXT GENERATED ALWAYS AS (IFNULL(doc_path, '')) STORED,
    scope         TEXT    NOT NULL,
    password_hash TEXT,
    expires_at    TEXT,
    revoked       INTEGER NOT NULL DEFAULT 0,
    invalid       INTEGER NOT NULL DEFAULT 0,
    views         INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_shares_token ON shares (token);
CREATE UNIQUE INDEX IF NOT EXISTS uk_shares_target ON shares (owner_id, kb_id, target_key);
CREATE INDEX IF NOT EXISTS idx_shares_owner ON shares (owner_id, created_at);
CREATE INDEX IF NOT EXISTS idx_shares_kb ON shares (kb_id);

CREATE TABLE IF NOT EXISTS share_view_log (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    share_id     INTEGER NOT NULL,
    visitor_hash TEXT    NOT NULL,
    view_date    TEXT    NOT NULL,
    created_at   TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_view_dedup ON share_view_log (share_id, visitor_hash, view_date);

CREATE TABLE IF NOT EXISTS user_settings (
    user_id    INTEGER PRIMARY KEY,
    settings   TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS kb_favorite (
    user_id    INTEGER NOT NULL,
    kb_id      INTEGER NOT NULL,
    created_at TEXT    NOT NULL,
    PRIMARY KEY (user_id, kb_id)
);

CREATE INDEX IF NOT EXISTS idx_fav_kb ON kb_favorite (kb_id);
