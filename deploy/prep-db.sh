#!/bin/bash
# 在「已有的」MySQL 容器上准备 minidocs 的库与应用账号。
#
# 背景：这台机器上已经有一个 1Panel 管理的 MySQL，内存余量不多，再起一个实例不划算，
# 所以直接复用。脚本是幂等的，重复执行安全（密码会重置，应用侧需同步改 .env）。
#
# 用法：
#   MYSQL_CONTAINER=mysql ./prep-db.sh
#
# 只创建 minidocs 自己的库和账号，不触碰该实例里的其他库，也不改 root 密码。
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql}"
DB_NAME="${DB_NAME:-minidocs}"
DB_USER="${DB_USER:-minidocs}"

# 从容器环境变量里取 root 口令，不落在命令行参数里
ROOT_PASS=$(docker inspect "$MYSQL_CONTAINER" \
  --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | sed -n 's/^MYSQL_ROOT_PASSWORD=//p')

if [ -z "$ROOT_PASS" ]; then
  echo "错误：容器 $MYSQL_CONTAINER 的环境变量里没有 MYSQL_ROOT_PASSWORD" >&2
  exit 1
fi

# 应用库专用账号：DbMigrator 启动时要建表改表，所以要给到库级 ALL（含 DDL）。
# 不用 full-schema-mysql.sql 那条路，是因为那条要求事先导入并手工确认 schema_version，
# 而这里希望首次启动就自动迁移。
#
# 口令只留字母数字：避开引号、反斜杠、$ 等字符，省掉一层 JDBC 连接串的转义地雷
DB_PASS=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 20)
[ ${#DB_PASS} -eq 20 ] || { echo "错误：生成口令失败" >&2; exit 1; }

docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$ROOT_PASS" <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASS}';
ALTER USER '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'%';
FLUSH PRIVILEGES;
SQL

echo "库 ${DB_NAME} 与账号 ${DB_USER} 已就绪"
echo "DB_PASSWORD=${DB_PASS}"
