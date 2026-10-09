-- MiniDocs 生产环境 DDL（MySQL 8）：V9 · 分享页顶栏的开源仓库入口
--
-- 目的：让读者能从分享页一键跳到该库对应的开源仓库。
--
-- 两个字段而不是一个：
--   repo_url  归一后的绝对地址（写入口已挡掉非 http/https 协议）
--   show_repo 按钮是否显示。分开存是为了「先配好、审阅期先藏起来」这一档；
--            若按「有地址就显示」实现，临时撤按钮只能删地址，删完还得记住它是什么。
--
-- 默认 0：不配仓库的分享（绝大多数）行为完全不变，历史行也不用回填。
ALTER TABLE shares ADD COLUMN repo_url VARCHAR(512) NULL;
ALTER TABLE shares ADD COLUMN show_repo TINYINT(1) NOT NULL DEFAULT 0;