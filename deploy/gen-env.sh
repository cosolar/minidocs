#!/bin/bash
# 生成 deploy/.env：随机生成 JWT 密钥与管理员口令，只把数据库口令作为参数传进来。
#
# 用法：
#   ./gen-env.sh <数据库密码> [对外地址]
#
# 幂等性：每次执行都会**重置** JWT_SECRET 与 ADMIN_PASS。
# 换掉 JWT_SECRET 后，所有已签发的登录态立即失效（用户需重新登录），所以稳定运行期间别再跑这个脚本；
# 换掉 ADMIN_PASS 只影响「尚未改过密码的内置管理员」——管理员在控制台改过密码后，
# 这个值就不再起作用了（它仅用于初始化）。
set -euo pipefail

cd "$(dirname "$0")"

DB_PASSWORD="${1:?用法: gen-env.sh <数据库密码> [对外地址]}"
PAGE_BASE="${2:-}"

if [ -f .env ] && [ -z "${FORCE:-}" ]; then
  echo "错误：.env 已存在。确认要覆盖（会重置密钥与管理员口令）时加 FORCE=1 重新执行" >&2
  exit 1
fi

# 只用字母数字：避开 .env 解析里需要引号的字符（# 会被当注释、= 之后的内容解析行为也不同）
JWT_SECRET=$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 48)
ADMIN_PASS=$(openssl rand -base64 18 | tr -dc 'A-Za-z0-9' | head -c 16)
[ ${#JWT_SECRET} -eq 48 ] && [ ${#ADMIN_PASS} -eq 16 ] || {
  echo "错误：随机口令生成失败" >&2
  exit 1
}

cat > .env <<EOF
# 由 gen-env.sh 生成，请勿提交（已在 .gitignore / .dockerignore 中排除）
# 生成时间：$(date '+%F %T')

# ---- 镜像 ----
IMAGE_TAG=minidocs:1.2.0
WEB_IMAGE_TAG=minidocs-web:1.2.0

# ---- 对外入口 ----
# 只绑回环，对外由宿主机上的 OpenResty(1Panel) 反代到 9116。
# 临时用 IP:9116 直连调试时改成 0.0.0.0
WEB_BIND=127.0.0.1
WEB_PORT=9116
# 站点基址：分享链接、复制链接等一切对外地址的基址，务必与浏览器地址栏完全一致。
# 优先在「站点设置」页（/console/platform/site）里配，改完即生效、不必重启。
PAGE_BASE_URL=${PAGE_BASE}

# ---- 数据库（复用宿主机上已有的 MySQL 容器，账号由 prep-db.sh 创建）----
MYSQL_DATABASE=minidocs
MYSQL_USER=minidocs
MYSQL_PASSWORD=${DB_PASSWORD}

# ---- 应用 ----
ADMIN_USER=admin
ADMIN_PASS=${ADMIN_PASS}
JWT_SECRET=${JWT_SECRET}
REGISTER_ENABLED=false
SHARE_COOKIE_HOURS=12
LOG_LEVEL=info

# ---- 内存 ----
# JVM 堆 = APP_MEMORY_LIMIT × 0.75。同机还有别的服务时别往上调。
APP_MEMORY_LIMIT=1g
EOF

chmod 600 .env
echo "已生成 $(pwd)/.env"
echo "管理员初始口令：${ADMIN_PASS}  —— 登录后请立刻在控制台改掉"
