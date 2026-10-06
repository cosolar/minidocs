# 部署指南

面向要把 MiniDocs 装到一台服务器上的人。读完你应该能：选一种部署形态、产出前后端产物、配好
数据库与反向代理、把数据落到持久目录，并知道上线前必须改哪几个配置。

阅读顺序建议：先看 [2. 前缀与形态](#2-前缀与形态) 选定形态，再照着
[3. 方案 A](#3-方案-a前后端分离--nginx推荐) 或 [4. 方案 B](#4-方案-b单体只分发一个jar) 走，
最后过一遍 [9. 上线检查清单](#9-上线检查清单)。

开发与本地启动见 [DEVELOPMENT.md](DEVELOPMENT.md)；功能与权限规范见
[多租户知识库权限与功能规范.md](多租户知识库权限与功能规范.md)。

---

## 目录

- [1. 环境与依赖](#1-环境与依赖)
- [2. 前缀与形态](#2-前缀与形态)
- [3. 方案 A：前后端分离 + Nginx（推荐）](#3-方案-a前后端分离--nginx推荐)
- [4. 方案 B：单体（只分发一个 jar）](#4-方案-b单体只分发一个jar)
- [5. 方案 C：单 jar + SQLite（应急 / 内网）](#5-方案-c单-jar--sqlite应急--内网)
- [6. 数据目录与备份](#6-数据目录与备份)
- [7. 常驻运行与升级](#7-常驻运行与升级)
- [8. 配置参考](#8-配置参考)
- [9. 上线检查清单](#9-上线检查清单)
- [10. 库级配置 `.minidocs.json`](#10-库级配置-minidocsjson)
- [11. 库内图片](#11-库内图片)
- [12. 故障排查](#12-故障排查)

---

## 1. 环境与依赖

### 1.1 需要什么

| 组件 | 版本 | 用在哪 | 必需 |
| --- | --- | --- | --- |
| JDK | 17+ | 运行 `minidocs.jar` | 是 |
| Maven | 3.9+ | 构建后端（仅构建机需要） | 构建时 |
| Node.js | 20+ | 构建前端（仅构建机需要） | 构建时 |
| MySQL | 8.0 | `prod` profile 的数据库 | 方案 A / B |
| Nginx | 任意 | 托管静态资源 + 反向代理 | 方案 A |

后端无 Redis、无对象存储、无消息队列，**唯一依赖是「关系库 + 一个本地数据目录」**。
所以部署的复杂度主要来自两个地方：数据库账号要有 DDL 权限（启动时会自动建表 / 改表），
以及 `VAULT_HOME` 目录要做持久化（文档、头像、站点 Logo、日志都在里面）。

### 1.2 目录规划（以 Linux 为例）

```
/srv/minidocs/
├── minidocs.jar        # 后端产物
├── dist/               # 前端产物（front/dist 的内容）
├── data/               # VAULT_HOME：数据库文件 / 文档 / 头像 / 日志
├── minidocs.env        # 环境变量（权限 600）
└── logs/               # 可选：stdout 重定向或 journal 归档目录
```

---

## 2. 前缀与形态

项目里有**三条独立前缀**，部署时必须互相对齐，混用会出现「页面打得开但接口全 404」或
「分享链接指向内网地址」：

| 前缀 | 变量 | 默认值 | 定义处 | 作用 |
| --- | --- | --- | --- | --- |
| 页面基址 `PAGE_BASE` | Vite `base` | `/`（站点根） | `front/vite.config.ts` | 静态资源地址、vue-router base、页面内链 |
| 后端前缀 `BACKEND_BASE` | `CONTEXT_PATH` / `VITE_BACKEND_BASE` | `/minidocs` | `application.yml` + 构建变量 | 接口基址、头像/封面/正文内库链接 |
| 分享链接基址 | 站点设置页的「站点基址」，兜底 `PAGE_BASE_URL` | 空 | `site_config.config.baseUrl` → `minidocs.page-base-url` | 复制出去的分享链接（唯一出口） |

因此只有两种合法组合：

| 形态 | 页面 | 接口 | 构建方式 | 后端配置 |
| --- | --- | --- | --- | --- |
| **A 分离** | `https://host/` | `https://host/minidocs/api/**` | 默认 `npm run build` | `CONTEXT_PATH=/minidocs` + 站点基址 = `https://host` |
| **B 单体** | `https://host/` | `https://host/api/**` | `VITE_BACKEND_BASE= npm run build` | `CONTEXT_PATH=`（空）+ `FRONT_DIR=/srv/minidocs/dist` + 站点基址 = `https://host` |

> **不要混着配**：形态 A 的前端产物里请求打的是 `/minidocs/api`，形态 B 的打的是 `/api`。
> 配错了只能改构建变量重新 `npm run build`，改 Nginx 是救不回来的。

> **关于 `resources/static` 里那份旧产物**：`backend/src/main/resources/static/index.html` 引用的是
> `/minidocs/assets/...`，与 `front/dist`（`/assets/...`）不是同一次构建。该目录被 `.gitignore` 排除，
> 部署时选一种形态即可：**形态 A 用 Nginx 托管 `front/dist`（推荐）**，形态 B 用 `FRONT_DIR` 指向
> `front/dist`。若历史上把前端拷进过 `resources/static`，请清掉，避免和 `FRONT_DIR` 抢 `index.html`。

---

## 3. 方案 A：前后端分离 + Nginx（推荐）

页面由 Nginx 托管、接口反代给 jar。前后端同源，所以**不需要处理 CORS**
（后端的 `WebMvcConfig` 只放行 `localhost` / `127.0.0.1`，仅供 `npm run dev` 使用）。

### 3.1 构建产物

建议在 CI 或本地构建，服务器只放产物：

```bash
cd front
npm ci                        # 若环境变量 NODE_ENV=production，改用 npm ci --include=dev
npm run build                 # 产物：front/dist（prebuild 会先清空 dist）

cd ../backend
mvn clean package -DskipTests # 产物：backend/target/minidocs.jar（finalName=minidocs）
```

上传到服务器：

```bash
# dist 整体放到 /srv/minidocs/dist
scp -r front/dist/*            root@server:/srv/minidocs/dist/
scp backend/target/minidocs.jar root@server:/srv/minidocs/
```

### 3.2 初始化数据库

表**不需要手工导入**：`DbMigrator` 在 jar 启动时按 `classpath:db/migration/{dialect}/V*.sql`
顺序执行，用 `schema_version` 表记录已应用版本，脚本全部是 `CREATE TABLE IF NOT EXISTS`，幂等。

你只需要建一个空库，并给应用账号 DDL 权限：

```sql
CREATE DATABASE minidocs DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'minidocs'@'%' IDENTIFIED BY '你的强密码';
GRANT ALL PRIVILEGES ON minidocs.* TO 'minidocs'@'%';
FLUSH PRIVILEGES;
```

#### 不给应用 DDL 权限的做法

有些生产规范不允许应用账号拥有 DDL 权限。为此备了一份**完整初始化脚本**：

```
backend/src/main/resources/db/full-schema-mysql.sql
```

它是 V1~V7 合并后的**最终表结构**（含 V3 收紧后的 NOT NULL、V3 替换过的唯一索引、
V4 的 Git 来源列、V5 头像、V6 分享菜单、V7 站点配置），可直接导入：

```bash
mysql -u root -p --database=minidocs < full-schema-mysql.sql
```

导入后**必须确认脚本第 8.1 节的 `INSERT IGNORE INTO schema_version` 已执行**——
它把 V1~V7 登记为「已应用」，应用启动时 `DbMigrator` 读到就会跳过全部迁移，
于是应用账号只需要 `SELECT, INSERT, UPDATE, DELETE`：

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON minidocs.* TO 'minidocs'@'%';
```

> **少了这一段 INSERT 会启动失败**：应用会去执行 V2 的 `ALTER TABLE ADD COLUMN`，
> 而列已经存在，直接报错。这不是脚本写错了，是「导入最终结构」与「走迁移」两条路
> 必须在此处交接。
>
> 脚本整体幂等，可重复执行；初始数据一律 `INSERT IGNORE`，不会覆盖管理员已在后台改过的配置。
> 第 9 节有自检查询（应为 14 张表 / 7 条版本 / 1 行站点配置）。
>
> **仅适用于全新安装**。已有数据的旧库请继续用 `db/migration/mysql/V*.sql` 逐版升级，
> 那份才带数据回填语句。

> `utf8mb4_0900_ai_ci` 是 MySQL 8.0 专有排序规则，5.7 请换成 `utf8mb4_unicode_ci`，
> 迁移脚本中的 `JSON` 列在 5.7 上也能跑，但不建议。
>
> MySQL 默认的 `sql_mode` 中 `ONLY_FULL_GROUP_BY` 若与本项目查询冲突，可在连接串上追加
> `sql_mode=ANSI_QUARTERS`。

### 3.3 启动后端

```bash
mkdir -p /srv/minidocs/data

java -jar /srv/minidocs/minidocs.jar \
  --spring.profiles.active=prod \
  --server.forward-headers-strategy=framework \
  --minidocs.vault-home=/srv/minidocs/data \
  --minidocs.page-base-url=https://kb.example.com \
  --spring.datasource.url="jdbc:mysql://127.0.0.1:3306/minidocs?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false" \
  --spring.datasource.username=minidocs \
  --spring.datasource.password='你的强密码' \
  --minidocs.jwt-secret='至少32字节的随机串' \
  --minidocs.admin-pass='强密码' \
  --minidocs.register-enabled=false
```

后端监听 `9098`（`PORT` 可改），**只监听本机，靠 Nginx 对外**。

`--server.forward-headers-strategy=framework` 让后端识别 `X-Forwarded-Proto` / `X-Forwarded-For`，
否则它按 `http` 生成地址，分享链接会变成 `http://`。显式配了 `page-base-url` 时该参数可省，
但配上更稳（Nginx 侧仍建议只传一个 `https` 入口）。

> 随机生成 `jwt-secret`：`openssl rand -base64 48`。这个值一旦更换，所有已签发 token 立即失效。

### 3.4 Nginx 配置

```nginx
# /etc/nginx/conf.d/minidocs.conf
upstream minidocs_backend {
    server 127.0.0.1:9098;
    keepalive 32;
}

server {
    listen 80;
    server_name kb.example.com;
    # 上线时建议 301 到 https
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    http2 on;
    server_name kb.example.com;

    # 证书
    # ssl_certificate     /etc/nginx/ssl/kb.example.com.crt;
    # ssl_certificate_key /etc/nginx/ssl/kb.example.com.key;

    root /srv/minidocs/dist;
    index index.html;

    # 必须 >= 后端 spring.servlet.multipart.max-request-size(60MB)
    client_max_body_size 60m;

    access_log /var/log/nginx/minidocs.access.log;
    error_log  /var/log/nginx/minidocs.error.log;

    # 1) 接口、库内资源、Swagger 统一反代
    location /minidocs/ {
        proxy_pass http://minidocs_backend;
        proxy_http_version 1.1;
        proxy_set_header Connection        "";
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_connect_timeout 15s;
        proxy_read_timeout    120s;   # 导入 / 导出 / Git 同步可能较慢
        proxy_request_buffering off; # 50MB 上传走流式，避免先落 Nginx 磁盘
    }

    # 2) SPA 深链接回退：没有这行，/share/{token}、/console/{org}/kbs 刷新就 404
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 3) 静态资源长缓存（后端也发了 immutable，这里是双保险）
    location /assets/ {
        expires 365d;
        add_header Cache-Control "public, immutable";
    }
}
```

校验并重载：

```bash
nginx -t && systemctl reload nginx
```

如果后端在 Nginx 之外的所有地方都不该被访问，用 `firewall-cmd` / `ufw` 只放行 80/443。

### 3.5 验证

```bash
curl -I https://kb.example.com/                       # 期望 200
curl -s https://kb.example.com/minidocs/v3/api-docs | head -c 200   # 期望返回 OpenAPI JSON
curl -I https://kb.example.com/minidocs/swagger-ui/index.html
```

浏览器登录后，逐项过一遍：

- 头像、封面能正常显示（走 `/minidocs/avatars/**`、`kb/*/asset/**`）
- 复制分享链接，粘贴到无痕窗口能免密打开，且域名是公网域名
- `/console/{org}/kbs` 直接刷新不 404

---

## 4. 方案 B：单体（只分发一个 jar）

后端同时托管页面和接口，服务器上不装 Nginx 也行。适合内网、小规模、或者只想拷一个 jar 的场景。

```bash
cd front
VITE_BACKEND_BASE= npm run build        # 关键：接口前缀置空
cd ../backend && mvn clean package -DskipTests

# 把 front/dist 的内容放到 /srv/minidocs/dist
java -jar /srv/minidocs/minidocs.jar \
  --spring.profiles.active=prod \
  --server.servlet.context-path= \
  --minidocs.front-dir=/srv/minidocs/dist \
  --minidocs.page-base-url=https://kb.example.com \
  --spring.datasource.url="jdbc:mysql://127.0.0.1:3306/minidocs?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false" \
  --spring.datasource.username=minidocs \
  --spring.datasource.password='你的强密码' \
  --minidocs.jwt-secret='至少32字节的随机串' \
  --minidocs.admin-pass='强密码' \
  --minidocs.register-enabled=false
```

要点：

- `--server.servlet.context-path=` 传空值把 `/minidocs` 前缀去掉，接口变成 `/api/**`
- `--minidocs.front-dir` 指向静态目录，后端会注册 `/assets/**`（长缓存）和 `/**`（协商缓存）
- 启动时若找不到前端 `index.html` 会在日志里打 WARN，看到这条说明路径配错了
- 前面要放反向代理的话：`location / { proxy_pass http://127.0.0.1:9098; }` 加
  `client_max_body_size 60m;`，并传 `X-Forwarded-Proto`
- 生产环境建议直接用 HTTPS 终止（内网也建议），jar 本身不处理 TLS

---

## 5. 方案 C：单 jar + SQLite（应急 / 内网）

不装 MySQL，数据全落在一个目录里：

```bash
java -jar /srv/minidocs/minidocs.jar \
  --minidocs.vault-home=/srv/minidocs/data \
  --minidocs.page-base-url=https://kb.example.com \
  --minidocs.jwt-secret='至少32字节的随机串' \
  --minidocs.admin-pass='强密码' \
  --minidocs.register-enabled=false
```

- 走 `dev` profile（`SPRING_PROFILES_ACTIVE` 默认值），SQLite 库在 `VAULT_HOME/minidocs.db`，
  启动时自动建表
- 开启 WAL 与 `foreign_keys`，单进程写入足够，不要让多个实例同时指向同一个 `VAULT_HOME`
- **备份 = 打包整个 `VAULT_HOME` 目录**（建议先停服务，或用 `sqlite3 .backup` 单独导 db）
- 适合内网演示、小团队；并发写入多、用户量上来后建议迁到 MySQL（迁移脚本两套方言都在仓库里）

---

## 6. 数据目录与备份

所有本地数据都在 `VAULT_HOME` 下，目录结构由 `MiniDocsProperties` 派生、启动时自动创建：

| 路径 | 内容 | 丢了会怎样 |
| --- | --- | --- |
| `data/minidocs.db` | SQLite 库（仅 dev/SQLite 形态） | 账号、权限、分享全丢 |
| `data/vaults/` | 知识库文档正文与库内资源（按组织/库分目录） | 文档丢 |
| `data/avatars/` | 用户头像 | 头像丢 |
| `data/site/` | 站点 Logo | 站点配置丢 |
| `data/logs/` | 应用日志 | 仅影响排障 |

MySQL 形态下 `VAULT_HOME` 只剩文件与日志，库本身在 MySQL 里。

备份脚本示例（每天 03:17，保留 14 天）：

```bash
#!/usr/bin/env bash
set -euo pipefail
STAMP=$(date +%Y%m%d-%H%M%S)
DEST=/backup/minidocs/$STAMP
mkdir -p "$DEST"
tar -czf "$DEST/data.tar.gz" -C /srv/minidocs data
mysqldump --single-transaction --quick --default-character-set=utf8mb4 \
  -h 127.0.0.1 -u minidocs -p"$DB_PASSWORD" minidocs | gzip > "$DEST/minidocs.sql.gz"
find /backup/minidocs -maxdepth 1 -type d -mtime +14 -exec rm -rf {} +
```

> 用 `--single-transaction` 避免锁表；`tar` 里的 `data` 目录不要用 `-h` 跟随符号链接到别处。

恢复顺序：MySQL 库 → `tar -xzf data.tar.gz -C /srv/minidocs` → 启动服务。

---

## 7. 常驻运行与升级

### 7.1 systemd（推荐）

`/etc/systemd/system/minidocs.service`：

```ini
[Unit]
Description=MiniDocs
After=network.target mysqld.service
Wants=mysqld.service

[Service]
Type=simple
User=minidocs
Group=minidocs
WorkingDirectory=/srv/minidocs
EnvironmentFile=/srv/minidocs/minidocs.env
ExecStart=/usr/bin/java -Xms512m -Xmx1g -jar /srv/minidocs/minidocs.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

# 基础加固
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ReadWritePaths=/srv/minidocs/data /srv/minidocs/logs

[Install]
WantedBy=multi-user.target
```

`/srv/minidocs/minidocs.env`（**chmod 600**，只放敏感与可覆盖项）：

```bash
SPRING_PROFILES_ACTIVE=prod
PORT=9098
VAULT_HOME=/srv/minidocs/data
# 站点基址的兜底值。优先在「站点设置」页里配（/console/platform/site，改完即生效）；
# 本项只在页面上没配、或配置由平台注入不便入库时才需要。两种方式别填成两个不同域名。
PAGE_BASE_URL=https://kb.example.com
DB_URL=jdbc:mysql://127.0.0.1:3306/minidocs?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
DB_USER=minidocs
DB_PASSWORD=你的强密码
JWT_SECRET=至少32字节的随机串
ADMIN_PASS=强密码
REGISTER_ENABLED=false
LOG_LEVEL=info
```

```bash
chmod 600 /srv/minidocs/minidocs.env
systemctl daemon-reload
systemctl enable --now minidocs
systemctl status minidocs
journalctl -u minidocs -f
```

> `application.yml` 里的 `minidocs.jwt-secret` / `minidocs.admin-pass` / `DB_PASSWORD`
> 都走 `${ENV:默认值}`，所以环境变量优先于 jar 内配置，`env` 文件里写就够，不用改启动参数。
> `server.forward-headers-strategy` 只能走启动参数（`SERVER_FORWARD_HEADERS_STRATEGY`
> 不是合法的 relaxed binding 名，会被忽略）。

### 7.2 升级流程

```bash
# 1. 备份
tar -czf /backup/before-upgrade.tar.gz -C /srv/minidocs data
mysqldump --single-transaction -h 127.0.0.1 -u minidocs -p minidocs > /backup/$(date +%F).sql

# 2. 构建新版本并替换
cd front && npm ci --include=dev && npm run build
cd ../backend && mvn clean package -DskipTests
# 上传新 jar，Nginx 形态下先 rsync --delete dist（保留用户上传的文件，它们不在 dist 里）

# 3. 重启
systemctl restart minidocs
journalctl -u minidocs -n 100 --no-pager
```

数据库 schema 由 `DbMigrator` 在启动时按版本号顺序自动升级，**升级 jar 即等于升级 schema**，
不需要手工执行 SQL。回滚 jar 时数据库不会自动降级，所以备份必须做。

前端是静态产物，可以**先发新 dist 再重启后端**（反代到旧 jar 也不影响），实现几乎无感更新。

---

## 8. 配置参考

后端全部配置都支持环境变量注入，优先级：启动参数 > 环境变量 > `application.yml` 默认值。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `9098` | HTTP 端口 |
| `CONTEXT_PATH` | `/minidocs` | 后端 context-path，即**接口**前缀（页面不带） |
| `PAGE_BASE_URL` | 空 | 站点基址的**部署期兜底**。优先在「站点设置」页里配（改完即生效，不必重启）；本项只在页面上没配、或配置由平台注入不便入库时用 |
| `VAULT_HOME` | `./data` | 数据根目录；**生产必须用绝对路径** |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev`=SQLite，`prod`=MySQL |
| `DB_URL` | 见 `application-prod.yml` | 完整 JDBC 连接串，优先于 `DB_HOST` |
| `DB_HOST` / `DB_USER` / `DB_PASSWORD` | `localhost` / `root` / 空 | MySQL 连接信息 |
| `JWT_SECRET` | 内置开发密钥 | **生产必须覆盖**，≥ 32 字节 |
| `JWT_EXPIRES_IN` | `7d` | 登录态有效期 |
| `ADMIN_USER` / `ADMIN_PASS` | `admin` / `admin123` | 内置管理员，仅初始化时使用 |
| `REGISTER_ENABLED` | `true` | 自助注册开关；对外网建议 `false` |
| `SHARE_COOKIE_HOURS` | `12` | 分享口令免密时长 |
| `FRONT_DIR` | 空 | 仅方案 B 使用：外部前端产物目录 |
| `LOG_LEVEL` | `info` | 日志级别 |
| `DRUID_MONITOR_ENABLED` | `false` | 是否开放 Druid 池监控页（见 8.1） |
| `DRUID_FILTERS` | `stat` | Druid filter 链，置空即关闭 SQL 统计 |
| `DB_SLOW_SQL_MILLIS` | `2000` | 慢 SQL 阈值（毫秒），仅在挂了 StatFilter 时生效 |
| `SWAGGER_ENABLED` | `false`（prod） | 接口文档开关。prod 默认关闭：`/minidocs/swagger-ui` 与 `/v3/api-docs` 公开可访问，等于把全部接口形状摊给访客 |

### 8.1 Druid 连接池

后端连接池是 **Druid**（`druid-spring-boot-3-starter`），参数按方言写在各自的 profile 里：

| 参数 | dev / test（SQLite） | prod（MySQL） |
| --- | --- | --- |
| `initial-size` / `min-idle` | 1 | 5 |
| `max-active` | 8 / 4 | 20 |
| `max-wait` | 10s | 30s |
| `test-while-idle` | `false`（SQLite 建连接只是开本地文件，校验是纯开销） | `true` + `validation-query: SELECT 1` |
| `filters` | 不挂 StatFilter | `stat`（SQL 统计 + 慢 SQL 日志） |

要临时调连接数，加启动参数或环境变量即可，例如
`SPRING_DATASOURCE_DRUID_MAX_ACTIVE=50`（Spring 的宽松绑定会把
`SPRING_DATASOURCE_DRUID_*` 映射到 `spring.datasource.druid.*`）。

**监控页默认关闭**。需要时在 `minidocs.env` 里加：

```bash
DRUID_MONITOR_ENABLED=true
DRUID_MONITOR_USER=admin
DRUID_MONITOR_PASSWORD=换成强密码
```

然后访问 `https://kb.example.com/minidocs/druid/index.html`（路径带 `context-path` 前缀）。
三点注意：

- 这个 servlet **不在 `AuthInterceptor` 的拦截范围内**（后者只拦 `/api/**`），认证完全依赖它自己的账号密码；
  `login-username` 为空等于不设防，所以两个变量必须都配
- 默认 `allow: 127.0.0.1`，只有本机能访问。要从别的机器看就改成自己的网段，或走 SSH 隧道：
  `ssh -L 18080:127.0.0.1:9098 root@server`，再开 `http://127.0.0.1:18080/minidocs/druid/index.html`
- 监控页的 SQL 列表里 `merge-sql: true` 已把参数值并成 `?`，不会把用户数据摆出来

`max-wait` 一定要给上限：Druid 默认是 `-1`（无限等待），MySQL 宕掉时会把 Tomcat 工作线程全部挂住并最终拖垮整个服务。

> **Druid 与 Hikari 的一个行为差异**：Hikari 是懒加载（第一次 `getConnection` 才建连），
> Druid 在容器启动时就 `init()` 并按 `initial-size` 建连，连不上**直接启动失败**。
> 这通常是好事（配置错、数据库没起来、目录不存在会在启动时暴露，而不是等到第一个用户请求），
> 但要求：**SQLite 形态下 `VAULT_HOME` 指向的目录必须先存在或可创建**，且它要和
> `--minidocs.vault-home` 指向同一个目录（前者是环境变量，后者是 Spring 配置项，两个独立来源）。

体积上限（`application.yml`，需要改就改配置文件或用 `--minidocs.xxx`）：

| 配置 | 默认 | 对应 Nginx |
| --- | --- | --- |
| `spring.servlet.multipart.max-file-size` | 50MB | — |
| `spring.servlet.multipart.max-request-size` | 60MB | `client_max_body_size ≥ 60m` |
| `minidocs.doc-max-size` | 2MB | — |
| `minidocs.image-max-size` | 20MB | — |
| `minidocs.import-max-size` | 50MB | — |

---

## 9. 上线检查清单

**构建**

- [ ] `front/dist` 是一次全新构建的产物，`npm ci` 在 `NODE_ENV=production` 下带了 `--include=dev`
- [ ] 形态明确：A 用默认构建（`/minidocs/api`），B 用 `VITE_BACKEND_BASE=` 构建（`/api`）
- [ ] `backend/src/main/resources/static/` 里没有旧的前端产物和它抢 `index.html`

**配置**

- [ ] `JWT_SECRET` 换成了随机 32+ 字节（`openssl rand -base64 48`）
- [ ] `ADMIN_PASS` 换掉了 `admin123`，并且**登录后立刻在控制台改一次密码**
- [ ] 站点基址已配（**推荐在「站点设置」页配**，`/console/platform/site`，改完即生效）；若仍用环境变量，`PAGE_BASE_URL` 要带 `https://`
- [ ] 若走 `full-schema-mysql.sql`：已确认 `schema_version` 有 7 行、应用账号**没有** DDL 权限（见 [3.2](#32-初始化数据库)）
- [ ] `VAULT_HOME` 是绝对路径，且在持久化卷 / 备份范围内
- [ ] `REGISTER_ENABLED` 按需关闭
- [ ] `minidocs.env` 权限 600，属主与 `User=` 一致
- [ ] 若开了 `DRUID_MONITOR_ENABLED`，`DRUID_MONITOR_PASSWORD` 已换掉默认值 `admin`，且 `allow` 不是 `0.0.0.0` 之类全网段

**基础设施**

- [ ] MySQL 8.0、库为 `utf8mb4`、账号有 DDL 权限、连接串带 `serverTimezone=Asia/Shanghai`
- [ ] Nginx `client_max_body_size ≥ 60m`、`try_files $uri $uri/ /index.html`
- [ ] 只有 443 对外，9098 未暴露
- [ ] 传了 `X-Forwarded-Proto`，后端开了 `forward-headers-strategy=framework`
- [ ] 备份脚本已配 cron，并**实测恢复过一次**

**验收**

- [ ] 分享链接在无痕窗口免密打开，域名正确
- [ ] `/console/{org}/kbs`、正文里的库内链接、图片资源刷新不 404
- [ ] 导入一个含图片的 Markdown，上传不失败
- [ ] 移动端 / 窄屏下页面布局正常

---

## 10. 库级配置 `.minidocs.json`

放在**知识库根目录**（与 `README.md` 同级），跟着云库的 Git 一起走。以 `.` 开头，
所以它自己不会出现在目录树里。

### 隐藏规则

哪些文件与目录不展示。**工作区 → 顶栏「设置」→「隐藏配置」**里勾选即可，也可以直接写文件：

```json
{
  "hidden": ["drafts", "notes/private.md", "**/_*", "**/*.tmp.md"]
}
```

| 写法 | 例子 | 说明 |
| --- | --- | --- |
| 精确路径 | `"drafts"`、`"notes/private.md"` | 目录命中即**整棵子树**不再遍历 |
| glob | `"**/_*"`、`"drafts/*"` | JDK `glob:` 语法；`*` 不跨目录，`**` 跨目录 |

配置落在同一个文件里，与顺序清单共存（`order` 键）。旧格式（顶层每个数组键是「目录路径 → 有序数组」）
继续能读。

**生效范围**（三处口径必须一致，漏一处就会「目录里没有、搜却搜得到」）：

| 位置 | 行为 |
| --- | --- |
| 目录树 | 不显示 |
| 全文搜索 | 搜不到 |
| 文档计数 / 上下一篇 / 首篇 | 不计入、不参与 |
| 工作区 `?path=` 直达、单篇分享链接 | 404 |
| 磁盘文件 | **保留**（隐藏是「不展示」，不是「删除」） |

两个易踩的点：

- 配置写坏了只 `log.warn` 一条然后按「无配置」继续 —— 宁可多显示，也不会让文档凭空消失。
- 改完**刷新即可**生效：目录树缓存键带了配置文件的 mtime，不用等缓存轮完。

### 手工编辑

界面上勾选最省事；需要批量或用 glob 时直接改文件，改完刷新页面。
`order`（顺序清单）与 `hidden` 共用 `.minidocs.json`，保存时两个分区一起写回，不会互相抹掉。

---

## 11. 库内图片

### 目录树里的图片

markdown 引用的本地图片大多就躺在文档旁边的 `images/` 里。目录树会列出这些图片节点：

- 点击 → 右侧（工作区为编辑区，阅读页为正文区）切成预览面板，可缩放、可下载
- **不计入**文档数、不参与搜索、不作为「上一篇/下一篇」—— 它们不是文档

产品内上传的图片落在 `assets/`，该目录仍在 `SYSTEM_DIRS` 里被过滤，所以**不会**涌进目录树
（否则几百张上传图会把树淹掉）。

### 正文里的图片路径

| 写法 | 处理 |
| --- | --- |
| `![](images/a.png)` | 按当前文档所在目录解析 ✓ |
| `![](../shared/b.png)` | 逐级上跳，正常 ✓ |
| `![](/images/a.png)` | 按**库内绝对路径**处理 ✓（Obsidian 等导出工具爱写这种；早前原样放行会撞 SPA 回退，图片永远裂着） |
| `![](../../outside.png)` | 越出库根 → 显示占位块（虚线框 + 悬停说明），**不再静默消失** |
| `![](missing.png)` | 文件不存在 → 占位块，悬停提示「可能已被删除或改名」 |

占位块用的是 `.md-img-missing`（定义在 `front/src/shared/styles/markdown.css`，两侧页面共用）。
后端负责「路径非法」，前端 `enhanceMarkdown` 的 `onerror` 负责「文件不存在」——后者是浏览器行为，
后端管不到。

**图片仍然只在展示层被隐藏**：文件在磁盘上、通过服务器直接访问 vault 目录当然还能拿到。
要真正对外不可见，得靠发布边界（只创建你愿意分享的那部分）。

---

## 12. 故障排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 页面白屏、控制台 404 `/assets/*` | Nginx `root` 指错，或 `dist` 不完整 | 检查 `root /srv/minidocs/dist;` 与 `index.html` 是否存在 |
| 刷新 `/share/xxx` 404 | 缺 SPA 回退 | 加 `try_files $uri $uri/ /index.html;` |
| 接口全 404 | 前缀不一致：前端打 `/api` 而后端是 `/minidocs` | 用对应的 `VITE_BACKEND_BASE` 重新构建（改 Nginx 无效） |
| 分享链接指向内网地址 | 站点基址没配对 | 先看「站点设置」页的「站点基址」；没配则确认 `PAGE_BASE_URL` 含 `https://` 与对外域名 |
| 分享链接少一段路径（如少了 `/minidocs`） | 站点基址只填了域名、没带页面实际挂载的路径 | 站点基址补上路径，如 `https://kb.example.com/minidocs` |
| 上传大文件 413 | Nginx 体积限制 | `client_max_body_size 60m;` |
| 头像/封面 404 | 反代没覆盖资源路径 | 确认 `/minidocs/` 前缀覆盖了 `avatars`、`kb/*/asset`、`share/*/asset` |
| 启动报找不到 `index.html`（WARN） | 方案 B 未配 `FRONT_DIR`，或目录为空 | 配 `--minidocs.front-dir=/srv/minidocs/dist` |
| 启动报 `SQLITE_CANTOPEN`（连 `minidocs` profile 才会遇到） | `VAULT_HOME` 与数据源 URL 指向了不同目录：URL 里的 `${VAULT_HOME:./data}` 只认**环境变量** `VAULT_HOME`，不认 `--minidocs.vault-home` | 两者设成同一个值。SQLite 形态下用 `VAULT_HOME=/srv/minidocs/data java -jar ...` 或在 env 文件里写 `VAULT_HOME=...` |
| `Public Key Retrieval is not allowed` | MySQL 认证与连接串不匹配 | 连接串加 `allowPublicKeyRetrieval=true` |
| 登录后刷新就掉线 | 换了 `JWT_SECRET` 或后端时间偏差过大 | 固定 `JWT_SECRET`；服务器校时（`timedatectl`） |
| SQL 迁移失败 | 账号缺 DDL 权限 / 字符集不对 | 补 `GRANT`；确认库是 `utf8mb4` |
| 请求全部卡住不返回 | `max-wait` 过大或被改成 `-1`，Druid 无限等连接 | 确认 `spring.datasource.druid.max-wait` 有上限；MySQL 宕机时这是必然现象 |
| 监控页 404 / 打不开 | 默认关闭，或被 `allow` 拦住 | 置 `DRUID_MONITOR_ENABLED=true`；访问路径要带 `context-path` 前缀；`allow` 默认只放行本机 |
| 监控页能进但没有 SQL 列表 | StatFilter 没挂上 | 确认 `spring.datasource.druid.filters` 含 `stat`；`filter.stat.enabled` 别设成 `false`（否则 starter 连单例都不建） |
| 分享页字体不生效 | CSP 只放行 `cdn.jsdmirror.com`，内网离线取不到 | 功能不受影响；要彻底解决需字体自托管到 `front/public/fonts/` 并改 `readerFont.ts` 与 `SecurityHeaderFilter` |
| **外链图片 / 徽章全部裂开**（图床、`img.shields.io`），Network 面板显示「已屏蔽：csp」 | `img-src` 只放了 `'self' data: blob:`，外部域名一律被浏览器丢弃 | 两处**同时**放开：后端 `minidocs.csp-allow-external-images=true`（默认开）与 `deploy/nginx.conf` 里的 `img-src 'self' data: blob: https:`。CSP 多条并存取交集，只改一处不生效 |
| 外链图片裂开，但服务端日志里那条请求是 200 | **不是跨域问题**：`<img>` 是简单请求，不受 CORS 约束，浏览器不会发预检。服务端 200 + 页面空白，几乎总是 CSP | 查 F12 里那条请求的「已屏蔽」原因，别顺着跨域方向查 |
| 发版后「没生效」，强刷一下就好 | `index.html` 既无 `Cache-Control` 也无 `expires`，只有 `ETag`；浏览器套用启发式缓存，在有效期内直接用本地副本、连条件请求都不发 | 入口页设 `expires -1`（不要用 `add_header`，它会顶掉同级那些安全头）；`/assets/` 保持 `expires 365d` |
| `docker compose up -d` 后配置没生效 | 镜像 tag 相同（如 `minidocs-web:1.2.0`），Compose 判定无需重建，复用了旧容器 | 改部署配置后用 `--force-recreate`，或给镜像换 tag |
| 目录里少了某些文件/文件夹 | `.minidocs.json` 里配了 `hidden`，工作区「设置 → 隐藏配置」勾选的就是它 | 取消勾选即可。规则相对库根，如 `["drafts", "notes/private.md", "**/_*"]`；配坏的文件按「无配置」处理（宁可多显示，不会让文档消失） |
| 隐藏了目录，但改地址栏 `?path=` 仍打得开 | 直达路径的入口此前不套隐藏规则 | 现已在 `DocServiceImpl#read` 与单篇分享处拦截并返回 404；若仍有遗漏入口，按「凡能拿路径取到内容就得问一次」逐个排查 |
| 容器一直 unhealthy、反复重启 | 健康检查探的是 `/v3/api-docs`，而 prod 默认关闭 SpringDoc | 健康检查已改为探 `/api/portal/site`（无需登录的公开接口） |

日志位置：`VAULT_HOME/logs/`（`logback-spring.xml` 里定义了 4 个 appender），
或直接 `journalctl -u minidocs -f`。接口问题先看 `X-Trace-Id` 响应头，
它与日志里的 traceId 对应。
