-- MiniDocs 开发环境 DDL（SQLite 3）—— V6：分享导航菜单
--
-- 分享页顶部那条导航条，由分享者从知识库目录树里挑几项（目录或单篇文档）再排序而来。
-- 配置随分享链接走，所以落在 shares 行上而不是知识库元数据里：同一条链接的菜单改动
-- 不该波及别的链接，同一个库的整库分享与单篇分享也可以各有各的菜单。
--
-- SQLite 没有 JSON 类型，用 TEXT 存 JSON 数组（与 user_settings 同一口径），形如
-- [{"type":"dir|doc","path":"相对路径"}]。路径在写入与读取时各校验一次：写入时按 vault
-- 现状定类型并剔除不存在的项，读取时再按树核对一次（配置是保存那一刻的快照，之后目录
-- 可能被删或改名）。因此不需要为失效条目单独写清洗任务。
--
-- SQLite 的 ADD COLUMN 一次只接受一列。

ALTER TABLE shares ADD COLUMN menu_config TEXT;
