-- ============================================================================
-- MiniDocs 完整初始化脚本（MySQL 8.0）
-- ============================================================================
--
-- 用途：一次性建出**当前代码所需的全部表结构**，供那些不便把 DDL 权限授予应用账号的
--       生产环境使用。导入本脚本后，应用启动时的 DbMigrator 会读 schema_version
--       发现自己已追上版本，于是跳过全部迁移 —— 因此不需要给应用 DDL 权限。
--
-- 与 db/migration/mysql/V1__init.sql … V7__site_config.sql 的关系：
--   本脚本 = 那 7 个迁移脚本**合并后的最终形态**（含 V2/V3 收紧后的 NOT NULL、
--   V3 替换过的唯一索引、V4 的 Git 来源列、V5 头像、V6 分享菜单、V7 站点配置）。
--   迁移脚本本身是主干资产、只读不改；本脚本是给部署用的派生产物。
--
-- ---------------------------------------------------------------------------
-- 适用场景（二选一，别混用）
-- ---------------------------------------------------------------------------
--   A. 全新安装：直接导入本脚本。
--   B. 已有旧库（已经跑过 V1~V7 的一部分）：**不要用本脚本**，改用迁移脚本逐版升级。
--      本脚本含 CREATE TABLE IF NOT EXISTS 与 ALTER 后的最终形态，对已有数据的库
--      既不会补齐缺失的列，也无法安全处理回填语句。
--
-- ---------------------------------------------------------------------------
-- 执行方式
-- ---------------------------------------------------------------------------
--   mysql -u root -p --database=minidocs < full-schema-mysql.sql
-- 或在 Navicat / DataGrip / MySQL Workbench 中整份执行（记得先选中目标库）。
-- 注意别用 --force 之类的容错开关：某条语句失败后继续跑，最终留下的半截表结构
-- 比直接失败更难查。
--
-- 幂等：全部语句可重复执行。表是 CREATE TABLE IF NOT EXISTS，初始数据一律 INSERT IGNORE，
--       不会覆盖管理员已在后台改过的配置。
--
-- ---------------------------------------------------------------------------
-- 版本要求
-- ---------------------------------------------------------------------------
--   MySQL 8.0+。用到了三处 8.0 特性：
--     - utf8mb4_0900_ai_ci 排序规则（5.7 需全局替换为 utf8mb4_unicode_ci）
--     - JSON 列（5.7 起可用）
--     - STORED 生成列（5.7 起可用）
--   本项目不用外键约束，与迁移脚本保持一致：引用完整性（删用户时不残留其知识库等）
--   由应用层保证。加外键会让「导入本脚本的库」与「走迁移的库」行为不一致，
--   且会在删除路径上抛出与业务语义不符的约束冲突。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 0. 建库与账号（按需取消注释）
-- ----------------------------------------------------------------------------
-- 选库：没写这一句，表会建到客户端的默认库里，而第 9 节的 DATABASE() 会返回 NULL，
-- 自检数字对不上却看不出原因。用 Navicat 之类图形客户端时也常见「导到了另一个库」。
-- 库名与第 0 节 CREATE DATABASE 里的保持一致。
--
-- USE minidocs;

-- 字符集必须是 utf8mb4：文档正文、站点名、用户昵称都可能含 emoji 与生僻字，
-- utf8mb3 的三字节上限会直接把它们截断或报错。
--
-- CREATE DATABASE IF NOT EXISTS minidocs
--     DEFAULT CHARACTER SET utf8mb4
--     COLLATE utf8mb4_0900_ai_ci;
--
-- 应用账号只需读写权限（DDL 已由本脚本完成）：
-- CREATE USER 'minidocs'@'%' IDENTIFIED BY '你的强密码';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON minidocs.* TO 'minidocs'@'%';
-- FLUSH PRIVILEGES;
--
-- 注意：上面刻意**不给** DDL 权限。若误给了，应用仍能正常启动（DbMigrator 读
-- schema_version 后无脚本可执行），但一旦有人手工往表上追加一条迁移记录，
-- 应用的 ALTER 会在生产库上执行 —— 这正是本脚本要避免的。


-- ----------------------------------------------------------------------------
-- 1. schema_version —— 必须最先建
-- ----------------------------------------------------------------------------
-- DbMigrator 用这张表判断「哪些迁移已应用」。本脚本导入的是最终形态，
-- 等价于 V1~V7 全部已执行，因此这里直接把七个版本登记进去，应用启动时
-- 会判定「已是最新版本」并跳过所有 DDL。
--
-- 结构与 DbMigrator.createVersionTable() 中的语句保持一致，不要改列名。
--
-- 不要手工往这张表加记录：加一条等于告诉应用「请在生产库上执行这个迁移」。

CREATE TABLE IF NOT EXISTS schema_version (
    version    VARCHAR(64)  NOT NULL,
    script     VARCHAR(255) NOT NULL,
    applied_at DATETIME     NOT NULL,
    PRIMARY KEY (version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ----------------------------------------------------------------------------
-- 2. 账号与权限
-- ----------------------------------------------------------------------------

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
    avatar_url    VARCHAR(255) NULL,        -- V5
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    -- email 允许 NULL，且 MySQL 的唯一索引不约束 NULL —— 这正是「不填邮箱的账号可以共存」
    -- 的原因。若改成 NOT NULL，未填邮箱的注册会直接撞唯一键。
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ----------------------------------------------------------------------------
-- 3. 组织（多租户地基，V2）
-- ----------------------------------------------------------------------------
-- type：TEAM 团队组织（可被发现、可申请加入）/ PERSONAL 个人组织（不可加入）。
-- status：active / disabled —— 账号是否可用只看 users.status，组织状态只表达组织本身。
-- 两者刻意分开，避免「审批通过要同时改两处」这种漏改。

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
    role        VARCHAR(16) NOT NULL DEFAULT 'MEMBER',   -- OWNER / ADMIN / MEMBER
    joined_from VARCHAR(16) NULL,                          -- bootstrap / invite / apply / transfer
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
    status           VARCHAR(16)  NOT NULL DEFAULT 'pending',  -- pending / approved / rejected
    reviewer_user_id BIGINT       NULL,
    reviewed_at      DATETIME     NULL,
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    -- 一个用户对一个组织只能有一条申请记录：重复申请会撞这一条唯一键，
    -- 应用层据此把「重复申请」当作幂等处理而不是报错
    UNIQUE KEY uk_join_request (tenant_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ----------------------------------------------------------------------------
-- 4. 知识库与文档
-- ----------------------------------------------------------------------------
-- tenant_id / storage_key / maintain_scope 三列来自 V2，V3 已把 NOT NULL 收紧到最终形态。
--
-- storage_key 是库目录的落盘相对路径，格式 o{tenantId}/{slug}。它落库而不靠 slug 推导：
-- 组织 slug 一旦改动，推导出的目录就变了，磁盘上的文件却还在老地方。
--
-- slug 自 V3 起只在组织内唯一（uk_kb_tenant_slug），所以不同组织可以叫同一个 slug。

CREATE TABLE IF NOT EXISTS knowledge_base (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT       NOT NULL,
    owner_id            BIGINT       NOT NULL,
    name                VARCHAR(64)  NOT NULL,
    slug                VARCHAR(64)  NOT NULL,
    storage_key         VARCHAR(255) NOT NULL,
    description         VARCHAR(255) NULL,
    visibility          VARCHAR(8)   NOT NULL DEFAULT 'private',  -- private / org / public
    maintain_scope      VARCHAR(12)  NOT NULL DEFAULT 'owner_only', -- owner_only / members / org_all
    cover_url           VARCHAR(600) NULL,
    tags                VARCHAR(255) NULL,
    doc_count           INT          NOT NULL DEFAULT 0,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    -- 以下 8 列来自 V4（云端 Git 来源）
    source_type         VARCHAR(16)  NOT NULL DEFAULT 'local',      -- local / git
    git_url             VARCHAR(512) NULL,
    git_branch          VARCHAR(128) NULL,
    git_username        VARCHAR(128) NULL,
    git_token           VARCHAR(1024) NULL,   -- 明文存储，见文末「安全须知」
    git_last_sync_at    DATETIME     NULL,
    git_last_sync_status VARCHAR(512) NULL,
    git_last_sync_ok    TINYINT(1)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_tenant_slug (tenant_id, slug),
    KEY idx_kb_owner (owner_id, updated_at),
    KEY idx_kb_visibility (visibility, updated_at),
    KEY idx_kb_tenant (tenant_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 知识库级名单：把某个库单独授权给组织外的人。角色 OWNER_ADMIN / EDITOR / VIEWER 语义见权限规范。
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

-- 编辑锁。状态落 DB 而非内存：进程重启丢锁会让两个人同时进编辑，正是这把锁要消灭的情况。
-- 过期行不删除，由下一个获取者就地接管，因此不需要清理任务。
CREATE TABLE IF NOT EXISTS doc_edit_lock (
    kb_id          BIGINT       NOT NULL,
    doc_path       VARCHAR(600) NOT NULL,
    holder_user_id BIGINT       NOT NULL,
    acquired_at    DATETIME     NOT NULL,
    expires_at     DATETIME     NOT NULL,
    PRIMARY KEY (kb_id, doc_path),
    KEY idx_doc_lock_holder (holder_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 常用知识库（收藏夹）。复合主键天然去重，无需额外的唯一索引。
CREATE TABLE IF NOT EXISTS kb_favorite (
    user_id    BIGINT   NOT NULL,
    kb_id      BIGINT   NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (user_id, kb_id),
    KEY idx_fav_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ----------------------------------------------------------------------------
-- 5. 分享
-- ----------------------------------------------------------------------------
-- 刻意没有 tenant_id：组织归属由 kb_id 那一跳决定，少一份会漂移的副本。
--
-- target_key 是 STORED 生成列，把 doc_path 的 NULL 归一成 ''，好让唯一键
-- 「一个目标只有一条分享」在整库和单篇两种情况下写法一致。
--
-- invalid 与 revoked 语义不同：revoked 是用户主动撤销；invalid 是条件失效
-- （文档被删、库被删）后连带作废，界面上要给出不同的提示。

CREATE TABLE IF NOT EXISTS shares (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    token         VARCHAR(64)  NOT NULL,
    owner_id      BIGINT       NOT NULL,
    kb_id         BIGINT       NOT NULL,
    doc_path      VARCHAR(600) NULL,
    target_key    VARCHAR(600) GENERATED ALWAYS AS (IFNULL(doc_path, '')) STORED,
    scope         VARCHAR(8)   NOT NULL,                       -- kb / doc
    password_hash VARCHAR(100) NULL,                           -- 口令哈希，NULL = 无口令
    expires_at    DATETIME     NULL,                           -- NULL = 永久有效
    revoked       TINYINT      NOT NULL DEFAULT 0,
    invalid       TINYINT      NOT NULL DEFAULT 0,
    views         INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    menu_config   JSON         NULL,                           -- V6：分享页导航菜单，NULL = 用默认
    PRIMARY KEY (id),
    UNIQUE KEY uk_shares_token (token),
    -- 唯一键不含 owner_id：归属由 kb_id 决定，挂在 owner_id 上会让「换所有者」时的
    -- 唯一性随组织归属漂移
    UNIQUE KEY uk_shares_target (kb_id, target_key),
    KEY idx_shares_owner (owner_id, created_at),
    KEY idx_shares_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 独立访客按天去重：(share, 访客, 日期) 唯一。
-- 会话级去重（30 分钟内多次取正文只计一次）走进程内计数，不落这张表。
CREATE TABLE IF NOT EXISTS share_view_log (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    share_id     BIGINT      NOT NULL,
    visitor_hash VARCHAR(32) NOT NULL,   -- IP + UA 指纹的哈希，不存原始 IP
    view_date    DATE        NOT NULL,
    created_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_view_dedup (share_id, visitor_hash, view_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ----------------------------------------------------------------------------
-- 6. 用户设置与审计
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS user_settings (
    user_id    BIGINT   NOT NULL,
    settings   JSON     NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 操作审计。detail 存结构化上下文，但**绝不记 token / 密码 / 口令**：
-- 审计表往往被直接翻给人看，一旦混进凭据就等于建了一个凭据泄露点。
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


-- ----------------------------------------------------------------------------
-- 7. 站点配置（V7）
-- ----------------------------------------------------------------------------
-- 全站只有 id = 1 这一行，故用 TINYINT 而非 BIGINT AUTO_INCREMENT。
--
-- config 是 JSON 列：站点级字段会慢慢长（页脚、备案号、首页横幅…），
-- 用一列 JSON 装它们，加字段时不必为每个新字段发一版迁移。
--
-- 表可以为空 —— 那是「从没配置过」的状态，服务层会回落到内置默认品牌，
-- 所以这里不预插空行，避免多出「先有鸡还是先有蛋」的初始化。
-- 想预置就取消注释第 8.2 节的 INSERT。

CREATE TABLE IF NOT EXISTS site_config (
    id         TINYINT  NOT NULL,
    config     JSON     NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ----------------------------------------------------------------------------
-- 8. 初始数据
-- ----------------------------------------------------------------------------

-- 8.1 登记迁移版本 —— **本脚本最关键的一段**
--
-- 应用启动时 DbMigrator 会 SELECT version FROM schema_version，
-- 比对出 V1~V7 全部已应用，于是判定「已是最新版本」直接放行。
-- 少了这一段，应用会去执行 V2 的 ALTER TABLE ADD COLUMN，
-- 而列已经存在 → 启动直接失败。
--
-- version 列的值是 '1'~'7'（文件名里 __ 之前的部分），不是 'V1'。
-- INSERT IGNORE 让重复导入本脚本时这段变成空操作。

INSERT IGNORE INTO schema_version (version, script, applied_at) VALUES
    ('1', 'V1__init.sql',           NOW()),
    ('2', 'V2__tenant.sql',         NOW()),
    ('3', 'V3__tenant_contract.sql', NOW()),
    ('4', 'V4__kb_git_source.sql',  NOW()),
    ('5', 'V5__user_avatar.sql',    NOW()),
    ('6', 'V6__share_menu_config.sql', NOW()),
    ('7', 'V7__site_config.sql',    NOW());


-- 8.2 站点配置（可选）
--
-- 预置一行，导入后立刻拥有站点名称、副标题与**站点基址**。
-- baseUrl 是分享链接的地址前缀（协议 + 域名 [+ 路径]），留空则回落到
-- 环境变量 PAGE_BASE_URL，再没有就按当前请求推导。
--
-- 反向代理后面务必填对，否则复制出去的分享链接会指向内网地址。
-- 下面的 kb.example.com 是 RFC 2606 保留的示例域名，**导入后请立刻改成真实域名**
-- （或改成在「站点设置」页里填），否则分享出去的链接会指向一个不存在的站。
-- 导入后也可以在「站点设置」页里改（/console/platform/site），改完立即生效。
--
-- INSERT IGNORE：管理员已在后台改过时不覆盖他填的值。

INSERT IGNORE INTO site_config (id, config, updated_at) VALUES
    (1, '{"name":"MiniDocs","subtitle":"极简知识库","baseUrl":"https://kb.example.com"}', NOW());


-- 8.3 初始管理员（可选，**默认注释**）
--
-- 通常不需要：应用启动时 AdminInitializer 会用 ADMIN_USER / ADMIN_PASS 自动建这个账号
-- （见 docs/DEPLOYMENT.md 的环境变量表），密码不必落在 SQL 里。
--
-- 只有这两种情况才手工插入：
--   · 想让初始密码与 ADMIN_PASS 无关（脚本交付时不便附带环境变量）
--   · 内置账号 ADMIN_USER 被置空、改由本脚本指定
--
-- 下面的 hash 对应明文 admin123。**换成自己的密码**再导入，
-- 否则等于公开了一个可被搜索引擎收录的默认口令。
--
-- 生成新 hash（用项目同款 BCrypt，需 spring-security-crypto 在 classpath）：
--   jshell --class-path spring-security-crypto-6.5.0.jar
--   > System.out.println(new org.springframework.security.crypto.bcrypt.BCrypt()
--       .hashpw("你的新密码",
--               new org.springframework.security.crypto.bcrypt.BCrypt().gensalt(10)));
--
-- 注意 role 为 ADMIN（平台角色，能进 /console/platform/**），
-- status 必须为 active，否则登录后被当作禁用账号拦下。

-- INSERT IGNORE INTO users (username, password_hash, display_name, role, status, created_at, updated_at)
-- VALUES ('admin', '$2a$10$9Laa9SqTVKs/NManqSYVCeHcxDXchtmU3HgmyY//uxtTCV0CJJAoa',
--         '超级管理员', 'ADMIN', 'active', NOW(), NOW());


-- ----------------------------------------------------------------------------
-- 9. 导入后自检
-- ----------------------------------------------------------------------------
-- 期望：14（13 张业务表 + schema_version）、7（迁移版本）、1（站点配置行）。
-- 少于这个数说明脚本没执行完，先别启应用。

SELECT COUNT(*) AS should_be_14_tables
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE();

SELECT COUNT(*) AS should_be_7_versions FROM schema_version;

SELECT id, config, updated_at FROM site_config;

-- 确认应用账号确实没有 DDL 权限（应返回空）。非空说明权限给多了：
-- SHOW GRANTS FOR 'minidocs'@'%';


-- ============================================================================
-- 安全须知
-- ============================================================================
-- 1. knowledge_base.git_token 明文存放访问令牌。本项目未做加密（数据库层加密会让
--    「换密钥」变成一次停机操作，代价大于收益），因此**数据库的访问控制与备份的
--    保密等级就是令牌的安全边界**。若你的备份会流向别处，请先确认这能被接受。
-- 2. 本脚本不含任何凭据明文；应用账号的密码在第 0 节里由你自己设置，且不要提交进版本库。
-- 3. git_token 建议在知识库设置页按需最小授权（只读 + 单仓库），并定期轮换。
-- 4. 分享口令存哈希（password_hash），但 Git 令牌存明文 —— 这个不对称是刻意的：
--    口令只需校验相等，令牌必须能再次取用。
-- ============================================================================
