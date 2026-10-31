# syntax=docker/dockerfile:1

# =============================================================================
# MiniDocs 官方镜像：前端静态资源 + Spring Boot，一个进程、一个端口、一个数据卷。
#
#   浏览器 ──> :9098  Spring Boot（页面 /，接口 /api/**，SPA 回退 /index.html）
#                        └── SQLite（默认）或外部 MySQL（SPRING_PROFILES_ACTIVE=prod）
#
# 与 deploy/Dockerfile.split 的区别：那边是 Nginx + 后端两个进程、两个镜像；
# 这里走「前端打进 jar、后端兼作静态服务器」的单体形态，部署只要一个容器。
#
# 构建（默认 linux/amd64）：
#   docker build -t <用户名>/minidocs:1.2.0 .
#
# 运行：
#   docker run -d --name minidocs -p 9098:9098 \
#     -e ADMIN_PASS='换成强密码' \
#     -e JWT_SECRET="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 48)" \
#     -v minidocs-data:/data \
#     <用户名>/minidocs:1.2.0
# =============================================================================

ARG NODE_VERSION=20-alpine
ARG MAVEN_VERSION=3.9-eclipse-temurin-17
ARG JRE_VERSION=17-jre-jammy

# ---------------------------------------------------------------- 1) 前端产物
FROM node:${NODE_VERSION} AS front-builder
WORKDIR /build/front

# 国内源。海外构建时改成 https://registry.npmjs.org
ARG NPM_REGISTRY=https://registry.npmmirror.com
# replace-registry-host=always 是关键：package-lock.json 里的 resolved 写的是公司阿里云效私有源
# （https://packages.aliyun.com/<orgId>/npm-registry/…），那个源要 _authToken，容器里没有就直接 E401。
# 这个选项让 npm 忽略 resolved 里的原始 host，一律换成上面配置的 registry；
# 默认值是 npmjs（只替换 registry.npmjs.org），替换不掉 aliyuncs 那类自定义 host。
# 依赖版本与 integrity 校验仍由 lock 决定，换源不会引入不一致的包。
RUN npm config set registry ${NPM_REGISTRY} \
    && npm config set replace-registry-host always

# 显式只取清单与源码；front/.npmrc（含明文 _authToken）已在 .dockerignore 排除
COPY front/package.json front/package-lock.json ./
# NODE_ENV=production 时 npm ci 会跳过 devDependencies，而 vite 正是 devDependency，故显式 --include=dev
RUN npm ci --include=dev

COPY front/ ./
# 单镜像里后端 context-path 为空，接口就在 /api；这里必须显式传空串把 VITE_BACKEND_BASE 置空。
# 只改后端 CONTEXT_PATH 而不改这里，页面会去请求 /minidocs/api -> 全站 404。
RUN VITE_BACKEND_BASE= npm run build


# ---------------------------------------------------------------- 2) 后端 jar（含前端产物）
FROM maven:${MAVEN_VERSION} AS backend-builder
WORKDIR /build/backend

# 只把 central 镜像到阿里云，详见文件内注释；海外构建可去掉 -s
COPY deploy/maven-settings.xml /build/maven-settings.xml

# pom 单独一层：只改依赖版本时才需要重新解析依赖
COPY backend/pom.xml ./
RUN mvn -B -q -s /build/maven-settings.xml dependency:go-offline

COPY backend/src ./src
# 仓库工作区里可能残留上一轮的前端产物（该目录被 .gitignore 排除），
# 先清掉再放本次构建，避免旧 chunk 被打进 jar。
RUN rm -rf src/main/resources/static
COPY --from=front-builder /build/front/dist ./src/main/resources/static

# 纯 Docker 构建里 dist 已就绪，测试交给 CI 工作流先行执行
RUN mvn -B -s /build/maven-settings.xml clean package -DskipTests \
    && mv target/minidocs.jar target/app.jar


# ---------------------------------------------------------------- 3) 运行镜像
FROM eclipse-temurin:${JRE_VERSION}

LABEL org.opencontainers.image.title="MiniDocs" \
      org.opencontainers.image.description="轻量级 Markdown 知识库与文档分享系统（前后端单镜像）" \
      org.opencontainers.image.source="https://github.com/cosolar/minidocs" \
      org.opencontainers.image.licenses="Apache-2.0"

# 日志与时区：logback 写 ${VAULT_HOME}/logs；容器里没有 tzdata 时时间戳是 UTC
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 minidocs \
    && useradd --uid 10001 --gid minidocs --home-dir /app --no-create-home --shell /usr/sbin/nologin minidocs

# 前端在站点根、接口在 /api：与构建时 VITE_BACKEND_BASE= 对齐
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=standalone \
    PORT=9098 \
    CONTEXT_PATH="" \
    VAULT_HOME=/data

WORKDIR /app
COPY --from=backend-builder /build/backend/target/app.jar /app/app.jar
COPY deploy/entrypoint.sh /app/entrypoint.sh
# Windows 工作区签出时脚本可能带 CRLF，shebang 会变成 "#!/bin/sh\r" 而无法执行；
# 构建期统一去掉 CR，避免「本地能跑、容器起不来」。
RUN sed -i 's/\r$//' /app/entrypoint.sh \
    && chmod 755 /app/entrypoint.sh \
    && mkdir -p /data/logs /data/vaults /data/avatars /data/site \
    && chown -R minidocs:minidocs /data

# 数据卷：文档正文、库内资源、头像、站点 Logo、主题包、SQLite 库都在 /data
VOLUME ["/data"]
EXPOSE 9098

# 用公开门户接口探活：无需登录、不返回正文，且不依赖 Swagger（生产默认关闭）
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://127.0.0.1:9098/api/portal/site -o /dev/null || exit 1

USER minidocs
ENTRYPOINT ["/app/entrypoint.sh"]
