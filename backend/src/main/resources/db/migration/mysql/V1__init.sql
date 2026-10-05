-- MiniDocs 生产环境 DDL（MySQL 8.0）

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(64)  NULL,
    email         VARCHAR(128) NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS knowledge_base (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id    BIGINT       NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    slug        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) NULL,
    visibility  VARCHAR(8)   NOT NULL DEFAULT 'private',
    cover_url   VARCHAR(600) NULL,
    tags        VARCHAR(255) NULL,
    doc_count   INT          NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_slug (slug),
    KEY idx_kb_owner (owner_id, updated_at),
    KEY idx_kb_visibility (visibility, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS shares (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    token         VARCHAR(64)  NOT NULL,
    owner_id      BIGINT       NOT NULL,
    kb_id         BIGINT       NOT NULL,
    doc_path      VARCHAR(600) NULL,
    target_key    VARCHAR(600) GENERATED ALWAYS AS (IFNULL(doc_path, '')) STORED,
    scope         VARCHAR(8)   NOT NULL,
    password_hash VARCHAR(100) NULL,
    expires_at    DATETIME     NULL,
    revoked       TINYINT      NOT NULL DEFAULT 0,
    invalid       TINYINT      NOT NULL DEFAULT 0,
    views         INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shares_token (token),
    UNIQUE KEY uk_shares_target (owner_id, kb_id, target_key),
    KEY idx_shares_owner (owner_id, created_at),
    KEY idx_shares_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS share_view_log (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    share_id     BIGINT      NOT NULL,
    visitor_hash VARCHAR(32) NOT NULL,
    view_date    DATE        NOT NULL,
    created_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_view_dedup (share_id, visitor_hash, view_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_settings (
    user_id    BIGINT   NOT NULL,
    settings   JSON     NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS kb_favorite (
    user_id    BIGINT   NOT NULL,
    kb_id      BIGINT   NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (user_id, kb_id),
    KEY idx_fav_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
