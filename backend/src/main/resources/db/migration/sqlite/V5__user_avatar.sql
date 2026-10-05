-- MiniDocs 开发环境 DDL（SQLite 3）—— V5：用户头像
--
-- 头像属于「人」而不是「组织」，故落在 users 表，而不是某个知识库的 vault 里。
--
-- 存的是<b>文件名</b>而非完整 URL：落盘位置由 minidocs.vault-home 决定，换部署目录时不必洗库。
-- 文件名形如 u{userId}-{时间戳}{随机}{扩展名}，换头像即换文件名，URL 跟着变，省掉一层缓存失效逻辑。
--
-- SQLite 的 ADD COLUMN 一次只接受一列。

ALTER TABLE users ADD COLUMN avatar_url TEXT;
