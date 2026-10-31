-- MiniDocs 生产环境 DDL（MySQL 8）：V10 · 库级配置入库
--
-- 目的：隐藏规则 / 目录顺序 / 展示偏好不再写进知识库根目录的 .minidocs.json。
--
-- 那个文件放在 Git 工作副本里、且是未跟踪文件，任何一次 git clean / 重新克隆都会把它删掉；
-- 平台只能在「应用内拉取成功」这一条路上做备份还原，别的路径（拉取失败、外部 git 操作）
-- 一律丢配置。配置本来就只属于这个部署，放进数据库才是它该待的地方。
--
-- 主键用 storage_key 而不是 kb_id：VaultFileService 只拿到知识库根目录，无法反查自增 id；
-- storage_key（o{tenantId}/{slug}）在库的整个生命周期里不变，由它反推目录是确定的。
-- 某一行存在即代表该库有非默认配置；三个分区都为空时直接删行，不保留空壳。
CREATE TABLE IF NOT EXISTS kb_config (
    storage_key VARCHAR(191) NOT NULL,
    config_json MEDIUMTEXT   NOT NULL,
    updated_at  DATETIME     NOT NULL,
    PRIMARY KEY (storage_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
