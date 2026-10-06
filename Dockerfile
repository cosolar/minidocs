# =============================================================================
# MiniDocs 多阶段构建
#
#   front-builder   node  → front/dist      （接口前缀 /minidocs，与后端 context-path 对齐）
#   backend-builder maven → minidocs.jar
#   app             JRE + jar，监听 9098，只在 compose 网络内暴露
#   web             Nginx + dist，站点根伺服页面，/minidocs/ 反代到 app
#
# 两个运行镜像分开的理由：后端带 context-path（/minidocs），前端产物 base 在站点根（/），
# 前缀不同源，只能由前置的 Nginx 合并成对外的一个入口。
#
# 构建：
#   docker build --target app -t minidocs:1.2.0 .
#   docker build --target web -t minidocs-web:1.2.0 .
# =============================================================================

ARG NODE_VERSION=20-alpine
ARG MAVEN_VERSION=3.9-eclipse-temurin-17
ARG JRE_VERSION=17-jre-jammy
ARG NGINX_VERSION=1.27-alpine

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

# 先只拷清单：源码改动时这一层仍命中缓存，不必重装依赖
# 显式只取这两个文件，front/.npmrc（含明文 _authToken）绝不能进构建上下文
COPY front/package.json front/package-lock.json ./
# NODE_ENV=production 时 npm ci 会跳过 devDependencies，而 vite 正是 devDependency，故显式 --include=dev
RUN npm ci --include=dev

COPY front/ ./
# 不传 VITE_BACKEND_BASE：默认的 /minidocs 就是后端 context-path 的默认值，两边天然对齐。
# 置空走「后端兼作静态服务器」的单体形态时，这里要改成 VITE_BACKEND_BASE= npm run build，
# 同时把 compose 里的 CONTEXT_PATH 置空——只改一边必然 404。
RUN npm run build


# ---------------------------------------------------------------- 2) 后端 jar
FROM maven:${MAVEN_VERSION} AS backend-builder
WORKDIR /build/backend

# 只把 central 镜像到阿里云，详见文件内注释；海外构建可去掉 -s
COPY deploy/maven-settings.xml /build/maven-settings.xml

# pom 单独一层：只改依赖版本时才需要重新解析依赖
COPY backend/pom.xml ./
RUN mvn -B -q -s /build/maven-settings.xml dependency:go-offline

COPY backend/src ./src
RUN mvn -B -s /build/maven-settings.xml clean package -DskipTests


# ---------------------------------------------------------------- 3) 运行镜像：后端
FROM eclipse-temurin:${JRE_VERSION} AS app

# 日志与时区：logback 写 ${VAULT_HOME}/logs，容器里没有 tzdata 时时间戳是 UTC
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    PORT=9098 \
    CONTEXT_PATH=/minidocs \
    VAULT_HOME=/data \
    LANG=C.UTF-8

WORKDIR /app
COPY --from=backend-builder /build/backend/target/minidocs.jar /app/app.jar

# VAULT_HOME 必须在进程启动前就存在：logback 的 file appender 依赖它，SQLite 形态更是直接开文件
RUN mkdir -p /data/logs /data/vaults /data/avatars /data/site

EXPOSE 9098

# 探测走公开门户接口，而不是 /v3/api-docs：
# prod profile 默认关闭 SpringDoc（对外暴露接口形状没有好处），
# 继续探 api-docs 会让容器在生产环境永远 unhealthy，进而被 compose 反复重启。
# /api/portal/site 无需登录、不返回正文，是最轻的存活探针。
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://127.0.0.1:9098/minidocs/api/portal/site -o /dev/null || exit 1

# forward-headers-strategy 只能走启动参数：SERVER_FORWARD_HEADERS_STRATEGY 不是合法的
# relaxed binding 名，挂在环境变量上会被静默忽略（DEPLOYMENT.md §7.1）
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]
CMD ["--server.forward-headers-strategy=framework"]


# ---------------------------------------------------------------- 4) 运行镜像：前端
FROM nginx:${NGINX_VERSION} AS web

COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=front-builder /build/front/dist /usr/share/nginx/html
