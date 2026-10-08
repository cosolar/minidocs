-- MiniDocs 生产环境 DDL（MySQL 8）：V8 · 分享的门户曝光范围
-- 详见 sqlite/V8__share_portal_scope.sql 的说明。
--
-- 落在 shares 表而不是 knowledge_base：曝光是「分享出去」这个动作的属性。
-- 一条整库分享就是一次发布，撤销或改范围都只动这一行，不必碰库本身。
--
-- DEFAULT 'anonymous' 与历史数据等价 —— 迁移前的所有分享都是「门户里所有人可见」，
-- 所以升级后行为不变，不会把谁的库突然从门户上摘掉。
ALTER TABLE shares ADD COLUMN portal_scope VARCHAR(16) NOT NULL DEFAULT 'anonymous';