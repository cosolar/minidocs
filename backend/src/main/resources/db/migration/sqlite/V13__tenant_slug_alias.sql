-- V13：组织标识改名后的历史别名。
--
-- 个人组织现在允许自己命名 slug，旧 slug 不能就此失效：阅读页地址 /kb/{org}/{slug}
-- 与收藏夹都可能还在用它。这里把旧值留成别名，findBySlug 命中别名时解析回同一个组织。
-- slug 仍是全局唯一：新组织不能占用别人的历史标识。

CREATE TABLE IF NOT EXISTS tenant_slug_alias (
    slug       TEXT    NOT NULL COLLATE NOCASE PRIMARY KEY,
    tenant_id  INTEGER NOT NULL,
    created_at TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tenant_slug_alias_tenant ON tenant_slug_alias (tenant_id);
