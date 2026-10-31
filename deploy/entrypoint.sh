#!/bin/sh
# MiniDocs 官方镜像入口。
#
# 为什么不在 Dockerfile 里写 ENV 默认值：ADMIN_PASS / JWT_SECRET 一旦有默认值，
# 用户很可能直接拿去公网跑，等于把后台口令和签名密钥公开。这里缺一个就拒绝启动，
# 把问题暴露在部署阶段，而不是等被登录或被伪造 token。
set -eu

if [ -z "${ADMIN_PASS:-}" ]; then
  echo "minidocs: 缺少 ADMIN_PASS（首次启动据此创建内置管理员）" >&2
  exit 1
fi

if [ -z "${JWT_SECRET:-}" ] || [ "${#JWT_SECRET}" -lt 32 ]; then
  echo "minidocs: 缺少 JWT_SECRET，或长度不足 32 个字符" >&2
  exit 1
fi

# JAVA_OPTS 故意不加引号：允许用户传 "-Xms256m -Xmx512m" 这样的多参数
# 单镜像不做 Nginx 反代合并，容器自身监听 9098；如果前面还有一层反代，
# forward-headers-strategy 让分享链接等对外地址能识别 X-Forwarded-Proto。
exec java ${JAVA_OPTS:-} \
  -XX:MaxRAMPercentage=75.0 \
  -Duser.timezone=Asia/Shanghai \
  -jar /app/app.jar \
  --server.forward-headers-strategy=framework \
  "$@"
