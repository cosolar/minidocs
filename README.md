# MiniDocs · 极简知识库平台

轻量化、高性能的 Markdown 知识库平台。**前后端彻底分离**：

- **后端** `backend/` —— Spring Boot 3 纯 REST API（MyBatis-Plus + Sa-Token/JWT），不写任何前端**源码**。
- **前端** `front/` —— Vue 3 + Vite，**一个 SPA、一次构建**：门户 / 阅读 / 分享 / 登录注册 / 组织管理（`/console/**`）/ 平台超管（`/platform/**`）都在同一张路由表里。
- **部署前缀** —— 页面与后端各有一层，互不牵连：前端 SPA 由 Nginx 伺服在**站点根**（Vite `base=/`、产物在 `front/dist/`），后端挂在 `server.servlet.context-path=/minidocs` 下（可用 `CONTEXT_PATH` 覆盖），接口与它拼出来的地址都带这一段。两侧前缀分别定义在前端 `src/shared/appBase.ts`（`PAGE_BASE` / `BACKEND_BASE`）与后端 `application.yml`。下文列出的页面路径都是**站点根之后**的部分。
- **产物** —— 前端构建输出到 `front/dist/`，交给任意静态服务器（Nginx / OSS / CDN）伺服在根；后端 jar 只含 API。

管理端页面用 Element Plus，**且只在落到 `/console/**`、`/login`、`/platform/**` 时才动态载入**（`src/admin/shell.ts` 是唯一的 EP 入口，`src/admin/views/**` 里没有任何 `element-plus` 静态 import）——门户与阅读页因此不会加载组件库，只拉侧边栏、树、查看器、编辑器和它自己那份样式。

源码仍按 `src/user/**`（门户 / 阅读 / 分享 / 编辑器）+ `src/admin/**`（登录注册 / 概览 / 组织管理 / 平台）+ `src/shared/**`（类型、请求层、设计变量、Markdown 渲染）三块组织。

---

## 1. 目录结构

```
minidocs/
├── backend/                                  Spring Boot 应用（纯 API）
│   ├── pom.xml
│   └── src/main/
│       ├── java/cn/minims/minidocs/
│       │   ├── common/                       统一响应、异常、工具、路径安全、限速、类型处理器、迁移器
│       │   ├── config/                       属性、MyBatis-Plus、Sa-Token、WebMvc（含静态资源映射）、OpenAPI
│       │   ├── auth/                         登录登出、账号资料、JWT、Cookie 桥接、内置管理员初始化
│       │   ├── user/                         用户、偏好设置、管理员用户管理
│       │   ├── tenant/                       组织（租户）CRUD、成员、入组申请、成员门拦截器、/api/me
│       │   ├── permission/                   AccessService 单一裁决点 + KbAction 权限轴（读/写/治理/分享）
│       │   ├── audit/                        操作审计写入与查询
│       │   ├── kb/                           知识库 CRUD / 收藏 / 统计 / 维护名单
│       │   ├── doc/                          目录树、文档读写、重命名移动、图片、导入导出、分工式编辑锁
│       │   ├── markdown/                     flexmark 渲染管线 + 白名单过滤 + LRU + ETag
│       │   ├── reader/                       阅读视图组装、资源代理
│       │   ├── portal/                       门户 API（/api/portal/**）+ 资源代理 + SPA 路由回退 + 老链接 301
│       │   ├── share/                        分享 API（/api/share/**）+ 资源代理 + 口令校验 + PV/UV
│       │   ├── search/                       L1 联想 + L2 全文检索
│       │   └── task/                         doc_count 与编辑锁过期定时校准
│       └── resources/
│           ├── application.yml               主配置
│           ├── application-dev.yml           SQLite（零外部依赖）
│           ├── application-prod.yml          MySQL 8.0
│           ├── logback-spring.xml            控制台 + 文件 + 错误文件，按天滚动保留 14 天
│           ├── db/migration/{sqlite,mysql}/  V1 基线、V2 租户表、V3 租户契约（列/约束收口）
│           └── static/                       前端构建产物（gitignore，由 front/ 构建直接写入）
│               ├── index.html                唯一入口
│               └── assets/**                 带内容 hash 的 JS / CSS 分块
└── front/                                    全部前端（一个 SPA）
    ├── package.json                          一次构建
    ├── index.html                            唯一入口 → static/index.html
    ├── vite.config.ts                        base=/，输出 front/dist/
    └── src/
        ├── main.ts / App.vue / router/       唯一的 Vue 实例与那张合并后的路由表
        ├── admin/                            登录注册 / 概览 / 组织管理 / 平台：api / stores / layouts / views / components / styles
        │   └── shell.ts                      Element Plus 的唯一入口（只被管理侧路由动态 import）
        ├── user/                             门户 / 阅读 / 分享 / 编辑器：api / views / components / composables / reader / styles
        └── shared/                           api 工厂与类型、设置 store、设计变量、Markdown 渲染、可见性文案
```

### 前后端边界（重要）

后端**不写**任何前端源码：`backend/src/main/resources/` 下**没有** `templates/`（Thymeleaf 已移除），也不含任何手写的前端代码。

前端**产物**由 `front/` 构建输出到 `front/dist/`（已在 `.gitignore` 中排除），交给静态服务器伺服在**站点根**；
jar 里不含前端页面。资源映射（`config/WebMvcConfig.java`）保留着，供「后端也伺服前端」的部署方式使用：

| 请求路径（context-relative） | classpath 位置 | 缓存策略 |
| --- | --- | --- |
| `/assets/**` | `classpath:/static/assets/` | `immutable, max-age=31536000`（文件名带内容 hash） |
| `/**` | `classpath:/static/` | `no-cache`（入口 HTML 与其它散落资源，靠 ETag 协商） |

> 上表是 `WebMvcConfig` 里的映射，**不含 context-path**。默认部署下页面由 Nginx 伺服在根（资源地址是 `/assets/…`），
> 这张表只在把 `CONTEXT_PATH` 设为空、后端兼作静态服务器时才生效；那时把 `minidocs.front-dir` 指向 `front/dist` 即可。

> 已关闭 Spring Boot 默认静态资源映射（`spring.web.resources.add-mappings: false`）——它同样注册 `/**`，
> 与上面的映射注册顺序会相互覆盖，导致首页解析结果不确定。springdoc 的 `/swagger-ui/**`、`/webjars/**`
> 均为显式注册，不受影响（已验证 `/swagger-ui.html` → `200`）。
>
> 若需要「不重新打包即替换前端产物」，可配置 `minidocs.front-dir` 指向外部目录，
> 其下内容会作为**追加**查找位置，`classpath` 始终优先。

- SPA 深链接回退由 `portal/controller/SpaFallbackController.java` 负责：`/`、`/kb/**`、`/share/**`、
  `/login`、`/register`、`/account`、`/discover`、`/console/**`、`/platform/**` 一律 forward 到唯一的 `/index.html`。
  **顶层前缀必须显式枚举**——控制器映射优先级恒高于静态资源处理器，用 `/**` 会把 `/assets/**` 一并吃掉。
  新开前端页面时只要落在这几个前缀里就不用改这里；开了新的顶层前缀才补一条。
- 老管理后台地址 `/admin/**` 整前 302 到 `/console`（组织落地页），由它读 `/api/me` 决定去哪个组织。
- 更早的组织管理台前缀 `/w/**` 由 `portal/controller/LegacyConsoleRedirectController.java` 301 到 `/console/**`
  （用户可能收藏过工作区地址），查询串原样带上。

运行期数据目录（`VAULT_HOME`，默认 `./data`）：

```
data/
├── minidocs.db     # 开发环境 SQLite 库
├── logs/           # 应用日志
└── vaults/         # 知识库根目录，两级：o{组织ID}/{slug} —— 一个知识库一个目录
```

> `vaults/` 下的目录名由 `storage_key` 决定，**不等于知识库 slug**：slug 只在组织内唯一，两个组织可以有同名
> slug，靠 `o{组织ID}/` 这一层区分。`storage_key` 一经写入不再改变（规范 I4），所以改名与挪目录是两件事。

> ⚠️ `./data` 是**相对当前工作目录**解析的。用不同方式启动（命令行 `cd backend`、IDEA 默认工作目录为项目根、`java -jar`）
> 会落到**不同的数据目录**，进而连到两套完全独立的库 —— 表现为「我建的知识库不见了」。
> **请始终显式指定 `VAULT_HOME` 的绝对路径**，详见 [3.2 数据目录](#32-数据目录vault_home务必显式指定)。

---

## 2. 环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.9+ |
| Node.js | 18+（本项目在 Node 22 / 24 下验证） |
| 数据库 | 开发：SQLite（内置）；生产：MySQL 8.0 |

---

## 3. 快速开始（开发环境）

```bash
# 1. 启动前端 dev server（页面在 http://localhost:5173/ ，接口自动代理到后端）
cd front
npm install --include=dev        # 若环境变量 NODE_ENV=production，必须显式加 --include=dev
npm run dev

# 2. 启动后端（默认 dev profile，SQLite，接口在 http://localhost:9098/minidocs/api）
cd ../backend
mvn spring-boot:run
```

> 两侧前缀不同：dev server 把 `/minidocs/api/**` 原样代理到 `9098`（后端自己按 context-path 解析），
> 页面本身不带前缀。后端换前缀时给前端 `VITE_BACKEND_BASE` 一起改，只改一边必然 404。

访问：

| 地址 | 说明 |
| --- | --- |
| `http://localhost:5173/` | 门户首页，游客可看 public 库 |
| `http://localhost:5173/kb/{org}/{slug}` | 知识库阅读页 |
| `http://localhost:5173/share/{token}` | 外部分享页 |
| `http://localhost:5173/login` · `/register` | 登录 / 自助注册 |
| `http://localhost:5173/console` | 管理后台落地页：读 `/api/me` 决定去哪个组织 |
| `http://localhost:5173/console/{org}/kbs` | 组织内的知识库管理 / 工作区 / 分享 / 组织设置 / 审计 |
| `http://localhost:5173/platform/users` | 平台用户管理（仅平台 ADMIN） |
| `http://localhost:5173/minidocs/swagger-ui.html` | 接口文档（由后端伺服，故带前缀；dev 下已代理） |

> 老的 `/kb/{slug}`（v1 链接）由 `LegacyKbRedirectController` 承接：全局只有一个同名 slug 时 301 到新地址，
> 多个则出消歧页。`/admin/**` 整前跳 `/console`，`/w/**` 301 到 `/console/**`。

默认管理员：`admin / admin123`（由 `ADMIN_USER` / `ADMIN_PASS` 初始化，首次启动后请立即修改密码）。

### 3.1 前端热更新开发

```bash
cd front
npm run dev          # :5173，/minidocs/api 与资源代理转发到后端（默认 http://localhost:9098）
```

> 开发地址为 `http://localhost:5173/minidocs/`（Vite `base` 与后端 context-path 一致）。
> 代理只转发 `/minidocs/api/**` 与 `/minidocs/kb/{org}/{slug}/asset/**`、`/minidocs/share/{token}/asset/**`，
> **不转发** `/minidocs/kb/{org}/{slug}`、`/minidocs/share/{token}` 本身——否则开发期直接刷新这两个地址会被后端接管，SPA 路由失效。
>
> 改完源码刷新页面即可（dev server 热更新）；要出生产产物用 `npm run build`，得到 `front/dist/`，交给静态服务器伺服在根。

### 3.2 数据目录（VAULT_HOME）务必显式指定

`minidocs.vault-home` 默认值是 `./data`，会**相对进程的工作目录**解析。这意味着同一份代码：

| 启动方式 | 工作目录 | 实际数据目录 |
| --- | --- | --- |
| `cd backend && mvn spring-boot:run` | `backend/` | `backend/data/` |
| IDEA 直接 Run（默认 `$PROJECT_DIR$`） | 项目根 | `<项目根>/data/` |
| `java -jar backend/target/minidocs.jar` | 执行命令时所在目录 | `<该目录>/data/` |

它们是**三套彼此独立的库**：各自的管理员账号、知识库、vaults 目录、分享记录都不同。切换启动方式时最容易踩的坑就是「数据不见了」——其实是连到了另一个库。

**统一做法：所有入口都显式指定绝对路径。**

```bash
# 命令行（bash）
VAULT_HOME=/abs/path/minidocs-data mvn spring-boot:run

# 命令行（PowerShell）
$env:VAULT_HOME='D:\project\minidocs\data'; mvn spring-boot:run

# 打成 jar 后
java -jar target/minidocs.jar --minidocs.vault-home=D:/project/minidocs/data
```

IDEA：Run/Debug Configurations → **Environment variables** 增加 `VAULT_HOME=D:\project\minidocs\data`
（或把 **Working directory** 改成 `$PROJECT_DIR$/backend`，使其与命令行行为一致）。

> 前端产物**不受工作目录影响**：它在 `front/dist/`，由静态服务器伺服。
> 只有主动配置了 `FRONT_DIR`（外部覆盖目录）时才需要关心路径，且同为相对工作目录解析。

> 如果 IDEA 里配置了 `ADMIN_PASS` 却发现登录不上：`ADMIN_USER` / `ADMIN_PASS` **只在目标库首次创建管理员时生效**，
> 对已存在的库不会覆盖密码。请改用已生效的密码，或登录后在「个人设置」中修改；
> 若确实忘记，可删除该库（或该 data 目录）后重新启动以重新初始化。

> 排查小技巧：`VAULT_HOME/logs/minidocs.log` 的启动日志里会打印实际使用的端口（`Tomcat started on port XXXX`），
> 多个实例若共用同一个 `VAULT_HOME`，日志会写到同一个文件，可据此区分是哪个实例在处理请求。

---

## 4. 生产部署（MySQL）

```bash
# 1. 构建前端（产物在 front/dist/，交给静态服务器伺服在根）
cd front && npm run build

# 2. 打包并启动后端（只含 API）
cd ../backend
mvn clean package -DskipTests
java -jar target/minidocs.jar \
  --spring.profiles.active=prod \
  --minidocs.vault-home=/data/minidocs \
  --minidocs.page-base-url=https://kb.example.com \
  --spring.datasource.url="jdbc:mysql://db:3306/minidocs?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false" \
  --spring.datasource.username=minidocs \
  --spring.datasource.password=******
```

Nginx 侧只需两件事：`/` 伺服 `front/dist`（深链接 `try_files $uri /index.html`），`/minidocs/` 反代到后端。
`--minidocs.page-base-url` 告诉后端分享链接该指向哪个页面根（不设则回落成带 `/minidocs` 的链接，
在分离部署下会白屏）。

> 仍想「只分发一个 jar」也行：把后端 `CONTEXT_PATH` 设为空、后端伺服 `front/dist`（配 `minidocs.front-dir`），
> 前端构建时设 `VITE_BACKEND_BASE=`（接口与页面同在根）。两种形态别混着配。

首次启动会自动建表并创建内置管理员。建议置于反向代理之后，并开启：

```yaml
server:
  forward-headers-strategy: framework
```

以便正确识别 `X-Forwarded-Proto`（影响 Cookie 的 `Secure` 属性与分享链接的 scheme）。

---

## 5. 配置项（环境变量优先）

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `VAULT_HOME` | `./data` | 数据根目录（数据库 / vaults / 日志）。**相对工作目录解析，建议一律写绝对路径**，见 [3.2](#32-数据目录vault_home务必显式指定) |
| `CONTEXT_PATH` | `/minidocs` | 后端 context-path，也就是**接口**前缀。页面不带这一层（页面在站点根），两侧一起改时给前端 `VITE_BACKEND_BASE` |
| `PAGE_BASE_URL` | 空 | 页面基址（站点根），用于拼分享链接。留空 = 沿用请求的 context-path（单 jar 部署）；前后端分离部署时必填，如 `https://kb.example.com` |
| `FRONT_DIR` | 空 | **可选**的前端产物目录（其下须有 `index.html` 与 `assets/`）。产物默认在 `front/dist/` 由静态服务器伺服在根，通常无需配置 |
| `PORT` | `9098` | HTTP 端口 |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev`=SQLite，`prod`=MySQL |
| `JWT_SECRET` | 内置开发密钥 | **生产必须覆盖**，≥ 32 字节 |
| `JWT_EXPIRES_IN` | `7d` | 登录态有效期 |
| `SHARE_COOKIE_HOURS` | `12` | 分享密码通过后的免密时长 |
| `ADMIN_USER` / `ADMIN_PASS` | `admin` / `admin123` | 内置管理员初始化 |
| `REGISTER_ENABLED` | `true` | 自助注册开关。注册者自带一个只有本人的个人组织，进不到别人的组织里；内网部署要收回入口时置 `false`，改由管理员建号 |
| `DB_HOST` / `DB_USER` / `DB_PASSWORD` / `DB_URL` | — | MySQL 连接信息 |
| `LOG_LEVEL` | `info` | 日志级别 |

其余业务阈值见 `application.yml` 的 `minidocs.*`（文档 2MB、图片 20MB、封面上限、层级 6 层、导入上限等）。

---

## 6. 安全设计要点

- **路径穿越**：所有文件操作统一经 `PathGuard` —— 规范化相对路径（拒绝 `..`、绝对路径、盘符、控制字符）→ 拼接知识库根 → 规范化后校验 `startsWith(root)`。
- **XSS**：flexmark 关闭原生 HTML 解析（`Parser.HTML_BLOCK_PARSER=false` + `SUPPRESS_HTML`），渲染结果再经 jsoup 白名单二次过滤；SVG 上传额外净化。
- **上传校验**：扩展名 ∈ 白名单，MIME 需以 `image/` 开头，文件头魔数与扩展名必须一致。
- **- **分享 token**：自动生成为 `S + 时间戳 + 6 位随机`（24 位），也允许维护者自定义 6-32 位短链（字母 / 数字 / `-` / `_`），短到这个长度仍够猜不到（6 位约 560 亿种）。读取侧与写入侧的格式校验必须一致（`ShareTokenUtil.VALID` 与 `isValidCustom`），否则自己设的链接自己打不开；唯一性跨组织全局（`uk_shares_token`），因为 `/share/` 这一层没有组织段。自定义短链只在创建时生效 —— 链接可能已发出去了，换 token 等于让旧链接当场失效。**：分享 token 匹配 `^[sS]\d{23}$`。
- **越权语义统一（规范 §2.5）**：读不到一律 `404`（不泄露存在性），读得到但写不到 `403`，非组织成员访问 `/api/console/{org}/**` 也是 `404`。判定只有 `permission/AccessService` 一个入口，业务代码不得自行复制规则；前端只读服务端下发的 `myPermissions` 决定按钮显隐。
- **组织上下文只来自路径**：`/api/console/{org}/**` 由 `TenantInterceptor` 解析成员门，`tenant_id` 不进请求体，避免「路径说 A、正文说 B」。
- **磁盘定位不经 slug**：知识库目录由不可变的 `storage_key`（`o{组织ID}/{slug}`）决定，缺失即快速失败，绝不回退到按 slug 猜目录（跨组织同名 slug 会串到别人的库）。
- **限速**：登录 5 次 / 60s（按用户名或 IP），分享密码 10 次 / 60s（按 token + IP）。注册接口不限速（要让人自助进门），靠 `REGISTER_ENABLED` 与用户名唯一性约束收口。
- **响应头**：`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`、CSP；SVG 代理覆写为 `default-src 'none'`。
- **Cookie 桥接**：登录时额外下发 HttpOnly / SameSite=Lax 的 `minidocs_token`，由 `TokenCookieBridgeFilter` 注入 `Authorization` 头，使 `<img>`、资源代理等无法设置请求头的场景也能走统一鉴权。
- **分享访问 Cookie 的作用域为 `/`**（而非 `/share/{token}`）：用户侧 SPA 的取数接口在 `/api/share/{token}`、资源代理在 `/share/{token}/asset/**`，与页面路径 `/share/{token}` 并不同前缀，只有放宽到根路径才能同时带上。令牌本身以分享 token 命名并 HMAC 签名，越界并无额外泄露面。

---

## 7. 接口约定（用户侧）

用户侧 SPA 用 HTTP 状态码表达业务分支，前端据此渲染对应页面：

| 接口 | 成功 | 语义化失败 |
| --- | --- | --- |
| `GET /api/portal/home` | `{stats, publicKbs, myKbs, user?}` | — |
| `GET /api/portal/kb/{org}/{slug}?path=` | `ReadView` | `404` 不可读的库 / 组织或库不存在 |
| `GET /kb/{org}/{slug}/asset/**` | 二进制（带 ETag） | `404` |
| `GET /api/portal/locate?slug=` | 该老 slug 命中的可见库列表（0 / 1 / 多） | — 唯一命中由后端直接 301，不走这里 |
| `GET /api/share/{token}?path=` | `{state:"ok", view}` 或 `{state:"password", kbName}` | `404` 已撤销 / 内容删除；`410` 已过期 |
| `POST /api/share/{token}/verify` | `200` + 免密 Cookie | `401` 口令错误；`429` 尝试过频 |
| `GET /share/{token}/asset/**` | 二进制 | `404` 未通过口令 / 已失效 |
| `GET /api/settings` · `PUT /api/settings` | 用户偏好 | `401` 未登录 |

`ReadView` 与后端 `reader/model/ReadView.java` 一一对应，其中 `assetPrefix` / `docLinkPrefix` 由后端按场景算好
（`/minidocs/kb/{org}/{slug}/asset/`、`/minidocs/kb/{org}/{slug}?path=`、`/minidocs/share/{token}/asset/`、
`/minidocs/share/{token}?path=`），前端直接拼接。这两类前缀是给浏览器直接请求的，因此**带全站前缀**
（`AppPaths` 统一补 `/minidocs`），不是 context-relative 的裸路径。
两个前缀都必须带组织段：slug 只在组织内唯一，少一段就会在跨组织重名时指向别人的库。

> 后端开启了 `jackson.default-property-inclusion: non_null`，**null 字段是「缺键」而不是显式 `null`**，
> 前端类型上按可选处理（如 `PortalHomeVO.user?`）。

---

## 8. 与需求文档的实现差异（重要）

| # | 文档方案 | 实际实现 | 原因 |
| --- | --- | --- | --- |
| 1 | Thymeleaf SSR 渲染门户 / 阅读 / 分享页 | 后端只出 JSON，页面全部由用户侧 Vue SPA 渲染 | 前后端分离：后端不写前端，前端统一在 `front/` |
| 2 | Flyway 管理双库迁移 | 自研 `DbMigrator`（约 150 行） | Flyway 社区版**不支持 SQLite**（无 `flyway-database-sqlite`）。脚本目录与命名保持 `db/migration/{dialect}/V*.sql`，`schema_version` 记录已应用版本，行为对齐 Flyway。 |
| 3 | 时间统一 ISO8601 文本 | 开发库写 `yyyy-MM-dd HH:mm:ss`，读取兼容带 `T`/毫秒/纯日期 | 等长同格式在 SQLite 中字典序 = 时间序，且 MySQL DATETIME 与 SQLite TEXT 双方言通用。API 输出仍为 ISO8601。 |
| 4 | yaml-front-matter 扩展 | 自研 `FrontMatterParser` | 只支持平台所需扁平结构，避免为解析 6 个字段引入额外扩展与依赖。 |
| 5 | anchorlink + toc 扩展生成锚点 | 由渲染后的 DOM 统一生成 `id` 与大纲树 | 保证「大纲 ↔ 标题 id ↔ 实际 DOM」三者永远一致，避免扩展生成的 id 与大纲不一致。 |
| 6 | Admonition / 代码工具栏 / KaTeX / Mermaid | 由用户侧 `src/user/reader/enhance.ts` 在浏览器端完成 | 服务端只输出安全 HTML，图表与公式按需动态加载（Mermaid 走 `import()` 分包，未命中时不下载）。 |
| 7 | 渲染缓存键 `kbId+path+mtime` | 键加入 `size` 与渲染变体，HTML 内以 `%%MD-ASSET%%` / `%%MD-DOC%%` 占位 | 一次渲染即可供门户 / 分享两入口复用，仅做字符串替换。 |
| 8 | L2 全文检索内存倒排索引 | 有界扫描（≤ 2000 篇） | 避免索引的启动构建 / 内存占用 / 一致性维护成本；单机知识库场景下响应足够。 |
| 9 | 目录树缓存按 `kbId + 目录 mtime` | 缓存键为知识库，写操作显式失效 | 语义等价且实现更简单，不会出现 mtime 精度导致的脏缓存。 |
| 10 | 标签数量来自文档 frontmatter | 左侧元信息展示知识库标签数 | 统计全库文档标签需遍历解析所有文件，代价与收益不匹配。 |
| 11 | 门户筛选 / 分页 / 视图切换 | 首屏取 12 条，筛选 / 排序 / 视图切换在客户端完成 | 游客无须登录即可浏览，同时避免为首页引入额外查询接口。 |
| 12 | MyBatis-Plus 经典包路径 | `IService`/`ServiceImpl` 位于 `com.baomidou.mybatisplus.spring.service` | MyBatis-Plus 3.5.9+ 的模块拆分（`mybatis-plus-spring`），分页插件在 `mybatis-plus-jsqlparser`。 |
| 13 | Element Plus 图标库 | 使用符号图标 | 减少一个前端依赖与打包体积。 |
| 14 | — | 分享响应里的 `views` 在计数后再读取 | `同一访客 30 分钟内翻文档、切上一篇都算同一次访问（`recordView` 返回本次是否计数，分享页据此拼「含本次」的累计值）；UV 另按 `share_view_log` 的 `(share, 访客, 日期)` 唯一键去重，是第二条轴 |
| 15 | — | 用户侧显式引入 `katex/dist/katex.min.css` | 原实现只引 JS 不引样式，公式会以未排版形态呈现 |
| 16 | — | 图片灯箱的 `mousemove` / `mouseup` 监听器在关闭时回收 | 原实现在每次打开灯箱时向 `window` 追加监听且从不摘除，反复开关会持续堆积 |

---

## 9. 已验证链路

**自动化**：`mvn test`（`backend/`）——**234 项用例，全绿**，覆盖权限矩阵（读 / 写 / 治理 / 分享 / 组织几条轴）、组织与成员治理、
入组申请、注册与个人组织、维护名单、编辑锁与并发写、审计（含按操作者与日期区间筛选、保留期清理、跨组织隔离）、
全站搜索（含命中链接）、门户跨组织与老链接迁移。另有 `AuthorizationGuardrailTest` 做静态守卫：`src/main/java` 里
出现角色字面量、`getOwnerId` 裸比较、`requireOwned` / `requireReadable` 复活都会让测试失败 —— 判定只能长在 `AccessService` 里。

**本机实跑**（`dev` + SQLite，`VAULT_HOME` 指向数据副本，端口 9099）：

- **构建**：`npm run build` 产出单一入口 `front/dist/index.html` + `assets/**`，静态服务器伺服在根。
- **EP 隔离**：Element Plus 只出现在懒加载的 `shell-*.js` / `shell-*.css` 分块里（421 条 `.el-*` 规则全在
  `shell.css`），门户与阅读页的分块一条都没有 —— 游客不会为管理组件库买单。
- **SPA 深链接**：`/`、`/login`、`/register`、`/account`、`/discover`、`/console/{org}/kbs`、`/platform/users` 刷新均 `200`；
  未注册的路径 `/nope` 仍 `404`（回退没有吞掉真实缺失）。
- **门户（游客）**：顶栏出「注册 / 登录」，两者都带 `?redirect=<当前页>`；注册成功后接着自动登录并回到原页。
- **管理侧**：`/account`、`/discover`、`/console/{org}/settings`（资料 / 成员 / 入组申请 / 危险区）、
  `/console/{org}/audit`（逐条留痕，含 `bypass=SUPER_ADMIN` 标记）、`/console/{org}/kbs` 均正常渲染。
- **导航栏（两条栏统一）**：栏内只有品牌、搜索、身份三类，动作一律留在页面里。管理端侧栏分「组织 / 个人 / 站点」三组
  且在任何管理页都在（切换组织只换高亮，不换整条栏），收起态 64px 图标居中并带 `title`；顶栏只剩折叠、组织切换器、
  页面标题、主题、用户五项。门户与阅读端顶栏为品牌 + 搜索胶囊（`Ctrl K`）+ 头像菜单（管理后台 / 账号设置 / 退出登录），
  菜单 `position: fixed` 且实测不被阅读页的四层 `sticky` 盖住，Esc 与外部点击均收起。图标全部换成真 SVG。
- **双轴权限（同屏）**：库卡片同时显示「谁能读」（本组织 / 私有 / 公开）与「谁能写」（仅我自己 / 维护名单 / 全组织可写）；
  「权限」面板可改档位并维护名单（EDITOR / VIEWER / 撤销），名单里的人不在候选授权列表中出现。
- **只读成员实测**：名单改为 VIEWER 后——卡片只剩「进入」；工作区顶栏无「分享」、「保存」禁用；
  锁条给出「你在这个库上是只读成员 + 怎么变成可写」；编辑器 `readOnly=nocursor`；目录右键只剩「下载 Markdown」。
  改回 EDITOR 后一切恢复。
- **组织切换器**：列出全部组织与角色徽标，当前组织置灰，另有「新建组织」与「发现其他组织…」。
- **按钮显隐读向量而非角色**：上面每一处的判据都是后端下发的 `myPermissions` / `tenantPermissions` / 分享行的
  `canGovern`，前端不再自己拼「OWNER 就能做 X」；实测把成员降为 VIEWER、把分享降为「仅创建者可管」时，
  按钮消失与后端 403 的口径一致（规范 §11.6）。
- **分享面板（库级与文档级共用一个组件）**：`ShareDialog.vue` 同时服务库列表卡片（整库）与工作区（当前文档，
  工具栏与目录右键都进得去）。有效期 / 口令 / 吊销实测生效：改「7 天」后回读 `expiresAt` 一致，吊销后原链接出
  失效页而不是空壳。
- **全站搜索（Ctrl/⌘+K）**：一次输入同时给名称 / 标签联想与正文匹配，正文单独成组并带片段，顶部给
  「共 N 处，显示前 M 条」；结果链接由后端算好（`SearchHit.url`），跨组织命中不会指错组织。
- **阅读端管理入口**：维护者从门户打开自己的库时顶栏多出「编辑」，空库时文案为「去工作区新建」；
  游客与只读成员看不到（判据是 `ReadView.canManage`，组织段取自 `ReadView.orgSlug`）。
- **审计筛选**：动作 / 操作者 / 日期区间 / 每页条数全部进地址栏；`?action=KB_MEMBER_MANAGE&actor=1` 只出该人该动作
  且总数跟着变，`?from=2026-09-25&to=2026-09-25` 覆盖当天全部条目；脏值（`?from=abc`、`?action=**`）一律当作没筛，
  接口侧 `?from=abc` 回 `40000 无法解析日期：abc` 而不是 500。

- **可见性三档在每一处都成三档**：门户页签（`全部 / 公开 / 本组织 / 私有`）、卡片徽标配色、阅读页侧栏文案、
  管理端小章配色、统计卡与 `StatsVO` 五处口径一致；配色取 `--md-vis-*`，深色档单独提亮过，
  实测暗色下「公开 / 本组织」两枚小章仍可读。开发库连发 20 个 `PUT /api/settings` 全部 `200`
  （`journal_mode=WAL&busy_timeout=5000`，见规范 §11.12；改之前第二条会 `SQLITE_BUSY` 打成 500）。
- **知识库管理页两种读法**：网格卡片与列表表格共用一个 `KbRowActions`（同一批按钮、同一套 `myPermissions` 门禁），
  切换真的换布局。列表一行一个库，含封面、所属目录、可见性、可写档位、篇数、更新时间与操作列；
  窄列下 EP 的 `.el-button + .el-button` 左边距会跟 flex `gap` 叠加导致换行，已在组件里清零。
- **全站视口高度（无页面级滚动条）**：12 条路由（门户 / 阅读 / `/account` / 登录 / 注册 / 概览 / 库列表 /
  工作区 / 审计 / 组织设置 / 分享页 / 404）实测 `documentElement.scrollHeight == clientHeight`，滚动只发生在
  `#md-view`（阅读页 8337/723）或管理端内部的 `.md-content` / `.md-ws__tree`；无换行裁切、无横向溢出。
  阅读页侧栏与大纲 `sticky top: 0` 固定不动、大纲 26 项可独立滚，正文滚到 1200/3000/6000 时高亮依次为
  章节 3/9/19；点大纲锚点落在滚动区顶部下方 78px（正好让开 46px 的粘性条）；前进/后退按路由恢复位置
  （4000 → 门户 → 回退仍 4000）。规范 §11.13。

> 未覆盖：窄屏（<900px）只做了 CSS 层面的让路规则与顶栏溢出量测，没有真机 resize 逐项点过。
> 视口高度这一套（阅读页侧栏/大纲的 `sticky top: 0` 与两处
> `calc(100dvh - var(--md-topbar-height))`，规范 §11.13）同样只在 1311×785 下点过，
> `dvh` 在移动浏览器地址栏收放时的表现需要真机确认；打印也只做了 CSS 层面的退回文档流，没有实际出纸。

---

## 10. 常用命令

```bash
# 后端（建议先设好 VAULT_HOME，避免数据目录随工作目录漂移）
cd backend
mvn compile                                       # 编译
mvn test                                          # 全量自动化测试
mvn spring-boot:run                               # 开发运行（默认 :9098）
VAULT_HOME=/abs/path/minidocs-data mvn spring-boot:run            # 指定数据目录
java -jar target/minidocs.jar --server.port=9098   # 指定端口
mvn clean package -DskipTests                     # 打包

# 前端（单 SPA：一个 dev server、一次构建）
cd front
npm run dev                   # 热更新（:5173，/api 与资源代理转发到 :9098）
npm run build                 # 先清空旧产物，再输出到 front/dist
```
