-- MiniDocs 生产环境 DDL（MySQL 8）—— V6：分享导航菜单
--
-- 说明见 sqlite/V6__share_menu_config.sql。

ALTER TABLE shares ADD COLUMN menu_config JSON NULL;
