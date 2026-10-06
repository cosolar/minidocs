# 开发指南

面向第一次接触这份代码、或想提交 PR 的贡献者。读完你应该能：本地跑起来、加一个页面 / 接口、
看懂权限与渲染这两块核心逻辑，并知道哪些约定不能碰。

阅读顺序建议：先看 [1. 环境与启动](#1-环境与启动) 把项目跑起来，再按需要跳读
[4. 后端开发](#4-后端开发) / [5. 前端开发](#5-前端开发)，动手前扫一遍
[8. 提交前检查清单](#8-提交前检查清单)。


## 1. 环境与启动

### 1.1 需要什么

| 工具 | 版本 | 备注 |
| --- | --- | --- |
| JDK | 17+ | 后端按 Java 17 编译 |
| Maven | 3.9+ | 需能拉取依赖；离线环境请先备好本地仓库 |
| Node.js | 20+ | Vite 6 的下限是 18，但 20 起更稳 |


### 1.2 拉代码与装依赖

```bash
git clone https://github.com/cosolar/minidocs.git
cd minidocs

cd front
npm install            # 若环境变量 NODE_ENV=production，改用 npm install --include=dev
cd ..
```

`NODE_ENV=production` 时 npm 会跳过 devDependencies（表现是「装完了但 `vite` 命令不存在」，
或者 `import.meta.env` 报类型错），加上 `--include=dev` 即可。

### 1.3 跑起来（开发模式）

两个终端各起一个：

```bash
# 终端 A：后端（dev profile + SQLite，无需额外配置）
cd backend
mvn spring-boot:run
```

```bash
# 终端 B：前端热更新
cd front
npm run dev
```

打开 <http://localhost:5173>：

- 页面在**站点根**（`http://localhost:5173/share/xxx`、`/console/{org}/kbs` …）
- 接口在 **`/minidocs/api`**，dev server 按后端 context-path 原样代理到 `127.0.0.1:9098`
- 默认管理员 **admin / admin123**（`ADMIN_USER` / `ADMIN_PASS` 初始化），登录后请立刻改密码
- 数据落在项目根的 `data/`（SQLite + 文档目录 + 日志），用 `VAULT_HOME` 可指到别处

想只用后端看页面：先 `cd front && npm run build`（产物 `front/dist`），再 `mvn spring-boot:run`，
访问 <http://localhost:9098> —— 页面同样在根，接口在 `/minidocs/api`。

### 1.4 前缀关系（改之前必读）

两侧前缀是**两件独立的事**，源码里分别定义，改一边必须改另一边：

| 前缀 | 默认值 | 定义处 | 谁在用 |
| --- | --- | --- | --- |
| 页面基址 `PAGE_BASE` | `''`（站点根） | `front/vite.config.ts` 的 `base` | 产物资源地址、vue-router base、页面内链 |
| 后端前缀 `BACKEND_BASE` | `/minidocs` | 构建变量 `VITE_BACKEND_BASE` | `API_BASE` 与后端拼出来的地址（头像 / 封面 / 正文内库链接） |
| 分享链接基址 | 空（按当前请求推导） | 后端 `site_config.config.baseUrl` → `minidocs.page-base-url` | 分享链接、复制链接 |

源码落点：前端 `front/src/shared/appBase.ts`（导出 `PAGE_BASE` / `BACKEND_BASE` / `API_BASE` /
`appHref()` / `appBackendHref()` / `stripAppBase()`），后端取值优先级集中在
`site/support/SiteBaseUrlResolver.java`（站点设置页配置 > `PAGE_BASE_URL` > 按请求推导），
拼装在 `share/support/ShareLinks.java`。

要改后端前缀：`CONTEXT_PATH`（后端）+ `VITE_BACKEND_BASE`（前端构建）一起改；
要改对外地址：在「站点设置」页（`/console/platform/site`）填「站点基址」，改完即生效；
部署期兜底才用 `--minidocs.page-base-url=https://your.host`，并让 Nginx 把 `/minidocs/` 反代过去。

---

## 2. 仓库地图

```
minidocs/
├── backend/                     Spring Boot 3 · MyBatis-Plus · Sa-Token · flexmark
│   └── src/main/java/cn/minims/minidocs/
│       ├── reader/              渲染与阅读视图（门户 / 分享共用一条管线）
│       ├── kb/ · doc/           知识库 · 文档（编辑锁、标签、frontmatter）
│       ├── share/               分享、口令、访问统计、短链
│       ├── tenant/              组织、成员、入组申请、组织段解析拦截器
│       ├── permission/          权限裁决（AccessService）与动作枚举
│       ├── audit/               操作审计与保留期清理
│       ├── markdown/            flexmark 渲染、占位符替换、渲染缓存
│       ├── search/              内存倒排索引全文检索
│       ├── portal/              门户数据、旧链接重定向、SPA 回退
│       ├── auth/ · user/        登录态、账号资料
│       ├── site/                站点品牌与站点基址（设置页可改，配置在 site_config）
│       └── common/              ApiResponse、异常、路径守卫、Web 配置、安全响应头
│   └── src/main/resources/
│       ├── application.yml      端口、context-path、minidocs.* 阈值
│       └── db/migration/{sqlite,mysql}/V*.sql    内容迁移脚本
├── front/
│   └── src/
│       ├── main.ts  App.vue  router/          入口、单 SPA 路由表与守卫
│       ├── user/                              门户 / 阅读 / 分享
│       ├── admin/                             登录注册 / 组织管理 / 平台治理（按需加载）
│       └── shared/                            请求层、设置 store、设计变量、渲染增强
├── docs/                         规范与开发文档（本文件在此）
└── README.md
```

依赖方向是单向的：`user` 与 `admin` 可以依赖 `shared`，反过来不行；`shared` **不得**依赖任何一侧的
`api` 模块（否则管理侧依赖会进阅读端首包）。需要跨侧能力时由 `main.ts` / `admin/shell.ts` 注册网关
（`registerSettingsGateway` / `registerSiteGateway`）。

---

## 3. 六条硬约定

这六条都有测试或审查兜底，破坏它们的改动会被打回：

1. **权限判定只写在 `permission/AccessService`**。业务代码里出现角色字面量、裸 `getOwnerId()` 比较、
   或自己判断「能不能改」，都会被 `AuthorizationGuardrailTest` 判失败。
2. **越权语义统一**：读不到一律 `404`（不泄露资源是否存在），读得到但写不到 `403`。
   不要为了「友好提示」把 404 改成 403。
3. **审计不记凭据**：分享 / 登录相关审计只记 id、开关、时间、计数，**绝不记 token、密码**。
4. **落盘路径走 `common/util/PathGuard`**：规范化相对路径 → 拼库根 → 校验 `startsWith(root)`。
   不要自己拼路径或用 `File` 直接处理用户输入。
5. **已发布的迁移脚本不改**：`V*.sql` 进过主干就只读不改（版本与校验和会被记录），新变更加新版本号，
   SQLite 与 MySQL 两份都要写。
6. **Element Plus 只在管理端**：`src/admin/shell.ts` 是唯一入口，门户与阅读页的产物里不应出现 EP。

---

## 4. 后端开发

### 4.1 分层与调用方向

```
Controller（鉴权、参数绑定、HTTP 语义）
   └─ Service（业务规则、事务边界）
        └─ Mapper（MyBatis-Plus，单表用 lambdaQuery）
```

- 控制器不写业务分支，只做「取上下文 → 调服务 → 装 `ApiResponse`」。
  用户侧用 HTTP 状态码表达分支（404 不存在 / 410 过期 / 429 限流），管理侧用 `ApiResponse` 信封。
- 事务加在服务实现的方法上：`@Transactional(rollbackFor = Exception.class)`。
- 需要当前用户 / 组织时用 `UserContext.require()` / `TenantContext.requireId()`，
  **不要**从参数里收 userId —— 组织段是唯一的组织来源（`tenant/interceptor/TenantInterceptor` 解析）。

### 4.2 权限与成员门

- 组织上下文只来自路径：`/api/console/{org}/**` 由成员门拦截器解析，`tenant_id` 不进请求体，
  避免「路径说 A、数据写进 B」。
- 「能不能读 / 写 / 治理 / 分享」统一问 `AccessService.can(kb, user, KbAction.X)`；
  前端只读服务端下发的 `myPermissions` 决定按钮显隐。
- 平台治理（`/api/platform/**`）用 `meta.platformAdmin` + 平台角色双闸。

### 4.3 Markdown 渲染管线

服务端只输出**安全 HTML**，浏览器端再增强（高亮、图表、公式、灯箱）：

```
ReaderService.build(kb, ReadRequest)
  → MarkdownService.render(...)      缓存键：kbId + path + mtime + size（+ 渲染变体）
  → HTML 中的 %%MD-ASSET%% / %%MD-DOC%% 占位符替换为带前缀的绝对地址
  → 返回 ReadView（正文 HTML、目录树、大纲、上下篇、面包屑）
```

- 占位符机制让「一次渲染、门户与分享两处复用」成立：两个入口的资产前缀与库内链接前缀不同。
- 新增渲染能力时不要在服务端塞前端交互（折叠、复制按钮等），那些属于
  `front/src/shared/enhanceMarkdown.ts`，且每个增强函数都必须**幂等**（预览区会反复调用）。

### 4.4 编辑锁与并发写

- `doc/service/DocLockService`：获取 / 续期 / 释放，TTL 60s，页面卸载时归还（`fetch keepalive`）。
- 保存走**乐观并发**：前端带 `size` + `mtime` 基线，基线不符返回 `412`，编辑器提示「已被他人修改」。
- 没有写权限的人打开文档**不发起** `acquireLock`（否则白占锁），依据是 `KbVO.myPermissions` 里有没有
  `DOC_WRITE`。

### 4.5 分享

- 一个库的一个目标（整库或某篇路径）只有一条分享：`uk_shares_target` 保证，接口是 upsert 语义。
- token：自动生成为 `S + 时间戳 + 6 位随机`（24 位），也允许自定义 6–32 位（字母数字与 `-_`），
  **唯一性跨组织全局**（`uk_shares_token`），因为 `/share/{token}` 这一层没有组织段。
- 自定义短链可在已有链接上更换（旧链接立即失效，界面先二次确认），审计只记「换过」这个事实。
- 访问量按**会话**计：同一访客（IP + UA 指纹）30 分钟内多次取正文只计一次；
  独立访客按 `share_view_log` 的 `(share, 访客, 日期)` 唯一键按天去重。
- 口令哈希存储；分享 Cookie 以 token 命名并 HMAC 签名，作用域为 `/`（取数接口在 `/api/share/{token}`、
  资源代理在 `/share/{token}/asset/**`，与页面路径并不同前缀）。
- 对外绝对地址只有一个出口：`site/support/SiteBaseUrlResolver.resolve(request)`，
  拼装在 `share/support/ShareLinks.java`。取值优先级：**站点设置页配的基址** > `PAGE_BASE_URL` > 按请求推导。
  管理员改完站点设置立即生效（保存时清缓存），不必重启后端。

### 4.6 数据库迁移

- 启动时由 `common/db/DbMigrator` 执行 `db/migration/{sqlite,mysql}/V*.sql`，版本记在 `schema_version`。
- 时间统一 ISO8601 文本（`yyyy-MM-dd'T'HH:mm:ss`），读取端兼容空格 / 毫秒 / 纯日期三种形态。
- 双方言 SQL 必须成对写；SQLite 不支持 Flyway 的部分能力，别指望它。

### 4.7 安全

- `common/web/SecurityHeaderFilter` 下发统一安全响应头（CSP、`X-Content-Type-Options`、
  `X-Frame-Options`、`Referrer-Policy`）；SVG 代理会覆写为更严格的 CSP。
- CSP 默认只允许 `'self'`。若你引入外部字体等资源，**必须在同一个常量里显式加域名**，
  并在提交说明里写清理由。
- 路径穿越、上传校验（扩展名 / MIME / 魔数）、分享 token 形态：见 README 的安全要点。

### 4.8 测试

`mvn test` 会跑全部用例（13 个测试类 + 一个基类）：

| 测试类 | 覆盖 |
| --- | --- |
| `permission/AccessServiceTest` | 权限矩阵（读 / 写 / 治理 / 分享几条轴） |
| `permission/PermissionBaselineTest` | 组织与成员治理 |
| `permission/AuthorizationGuardrailTest` | **静态守卫**：出现越权写法即失败 |
| `tenant/*`（4 个类） | 建组织、入组申请、成员门、跨组织隔离、平台治理 |
| `doc/EditLockFlowTest` | 编辑锁、并发写 |
| `share/ShareOrgScopeTest` | 分享的组织隔离与口令 |
| `portal/PortalRoutingTest` | 门户数据与路由 |
| `migration/TenantMigrationTest` | 迁移脚本与唯一约束 |
| `meta/NullWritebackTest` | 写回不产生空值 |

写测试的约定：继承 `support/MiniDocsTestBase` 拿上下文；Surefire 已配置为每例重置 mock，
**不要**在用例之间共享 mock 状态。负例要选对组织：用 `public` 库测「非成员看不到」会因
「public 跨组织可见」而假通过。

---

## 5. 前端开发

### 5.1 路由与守卫

`src/router/index.ts` 一张表覆盖全站，路由 `meta` 字段：

| meta | 含义 |
| --- | --- |
| `reader: true` | 显示门户顶栏与搜索面板（页面在根） |
| `admin: true` | 进入管理外壳：动态 `import('@/admin/shell')`，装载 Element Plus |
| `public: true` | 无需登录即可进入（登录 / 注册页），外壳仍然装载 |
| `platformAdmin: true` | 需平台角色，否则回退到工作区 |
| `title: string` | 文档标题，拼成「页面名 · 站点名」 |

- 组织段一律在路径里（`/console/{org}/...`），不要放进 store 或请求体。
- 新增管理页：先在 `admin/views/` 建组件 → 路由加 `meta: { admin: true }` → 在 `admin/api/` 补方法。
  管理页是懒加载 chunk，别把大依赖静态 import 进视图。

### 5.2 请求层

- 底层工厂在 `shared/api/http.ts`（`createHttp` + axios 实例 + 统一错误信封），两侧各自建实例：
  用户侧静默、401 交给 `session` 处理；管理侧 401 清登录态并跳登录、其余弹 `ElMessage`。
- 业务模块互不引用：`user/api` 与 `admin/api` 各自成体系。
- 需要「不打扰用户」的失败（锁冲突、Git 拉取失败）加 `silentError: true`，由调用方自己呈现。

### 5.3 样式

| 文件 | 管什么 |
| --- | --- |
| `shared/styles/variables.css` | 设计变量（颜色、圆角、尺寸）与明暗两套主题 |
| `shared/styles/markdown.css` | Markdown 正文渲染（标题、列表、代码、表格、提示块、公式、图表、灯箱） |
| `user/styles/reader.css` | 门户 / 阅读页 / 分享页骨架与响应式 |
| `user/styles/portal.css` | 门户专属（统计卡、筛选、卡片网格） |
| `admin/styles/admin.css` | 管理端（含登录 / 注册页、分享面板、编辑器外壳） |

约定：

- 类名用 BEM 风格短横线（`.md-card__foot`），统一 `md-` 前缀，避免与 EP 类名相撞。
- **`:deep()` 只在 `<style scoped>` 里有效**。写在全局样式表里浏览器会当未知伪类，
  整条规则连同声明一起被丢弃（登录页踩过一次）。
- 明暗两套只改 `variables.css` 里的变量值，不要在组件里写 `if (dark)`。
- 状态变化用 `:hover` / `transition` / `color-mix()` 表达，别为了一次 hover 引入 CSS-in-JS。

### 5.4 图标

- 用户侧：`user/components/Icon.vue`；管理侧：`admin/components/MdIcon.vue`。
  两者都是内联 24×24 线性 SVG，**项目刻意不引图标库**。
- 加图标：在对应文件的 `PATHS` / `ICONS` 里加一组 path（描边继承 `currentColor`），名字用小写短横线。

### 5.5 浏览器端渲染增强

`shared/enhanceMarkdown.ts` 提供两个入口：全量增强（阅读页 / 分享页）与预览增强（后台 bytemd 预览区），
覆盖代码高亮、代码工具条、Admonition、Mermaid、KaTeX、图片灯箱、表格外壳。

**每个增强函数都必须幂等**：后台预览一次加载会跑好几轮后处理，靠 `dataset` 标记或「父节点 / 兄弟节点
已存在」判断去重，否则会重复包壳、重复绑定监听。

### 5.6 用户偏好（只在这台浏览器）

| 模块 | 存储键 | 作用范围 |
| --- | --- | --- |
| `shared/readerFont.ts` | `minidocs.reader-font` | 分享页正文字体 → `--md-read-font` |
| `shared/readerWidth.ts` | `minidocs.reader-width` | 分享页正文宽度 → `--md-doc-width` |
| `shared/stores/settings.ts` | `minidocs.settings` | 主题、内容宽度等（含服务端同步） |

- 分享页改主题用 `settings.patchLocal()`：**只写本地、不发网关** —— 访客没有账号，
  每点一次发一次必然 401。
- 本地偏好写在根元素的 CSS 变量上，消费方**按页面收窄**（`.md-reading[data-mode='share']`），
  别让「分享页的设置」波及门户阅读页。
- 读 `localStorage` 一律包 try/catch：隐私模式会抛错，不能因此白屏。
- 换字体这类偏好只改字体，**不要**顺手动行高与段距：那会让整篇的块高（引用块、表格）跟着变，
  比字形差异扎眼得多。

---

## 6. 常见任务

**加一个管理页**

1. `admin/views/XxxView.vue`（`<script setup lang="ts">`）
2. `router/index.ts` 加路由，`meta: { admin: true, title: '…' }`；组织段页面用 `ORG` 前缀常量
3. `admin/api/index.ts` 加接口方法
4. 样式写进 `admin.css`，复用既有件（`md-card-panel`、`md-ad-*`），别另起一套

**加一个用户侧接口**

`user/api/index.ts` 加方法 → 类型放 `user/api/types.ts`（两侧共用则放 `shared/api/types.ts`）。
控制器记得选对 HTTP 语义（不存在 404、过期 410、限流 429）。

**加一种服务端设置**

`config/properties/MiniDocsProperties` 加字段 → `application.yml` 写默认值与环境变量占位 →
若前端也要，在 `shared/stores/settings.ts` 里消费（`applyToDom` 会自动写根元素），
或构建期变量 `import.meta.env.VITE_*`。

**加一个 Markdown 提示块类型**

后端在 `markdown/` 的扩展解析里加；样式加到 `markdown.css` 的 `.md-admonition--*` 一族。
只想在浏览器端做也行（放 `enhanceMarkdown.ts`），但要记得幂等。

**改主题配色**

只改 `shared/styles/variables.css`；随后检查 `markdown.css` 里写死的 `rgba(...)` —— 那里的深浅色
已用 `color-mix` 与变量适配，个别装饰色是例外。

**加一条分享相关的统计口径**

访问量与访客数是两套去重规则（会话 vs 天），改动前先读 [4.5](#45-分享)，
并保证前端展示的累计值与后台列表一致（计数方法会返回「本次是否计入」供调用方拼值）。

---

## 7. 构建与测试

```bash
# 后端
cd backend
mvn test                     # 全量用例
mvn -o test                  # 离线（依赖已备好时）
mvn clean package -DskipTests

# 前端
cd front
npm run dev                  # 热更新
npm run build                # 产出 front/dist
```

CI 尚未接入（仓库暂无 `.github/workflows`），所以上面这两条命令就是门禁。

---

## 8. 提交前检查清单

- [ ] `cd backend && mvn test` 全绿
- [ ] `cd front && npm run build` 无报错，产物里管理端 EP 仍是独立 chunk
- [ ] 权限相关改动：判定只写在 `AccessService`，没引入角色字面量或裸 owner 比较
- [ ] 越权语义：读不到仍是 404
- [ ] 审计记录里没有 token / 密码等凭据
- [ ] 落盘路径走 `PathGuard`；上传校验三件套（扩展名 / MIME / 魔数）齐全
- [ ] 数据库变更：新增迁移版本，SQLite 与 MySQL 成对，已发布脚本未改
- [ ] 前缀没被写死（页面根 / 接口前缀 / 分享基址三者自洽）
- [ ] 改了共享逻辑（分享、渲染、目录树）→ 同步更新 `docs/` 下规范
- [ ] 注释解释「为什么」，而不是复述代码做了什么

提交信息沿用 Conventional Commits，例如
`feat(share): 支持自定义短链`、`fix(editor): 编辑锁续期失败导致误判只读`、`docs: 补部署说明`。
破坏性变更在正文首行写 `BREAKING CHANGE:` 说明。

---

## 9. 常见坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `npm install` 后没有 `vite` 命令 | `NODE_ENV=production` 跳过 devDependencies | `npm install --include=dev` |
| `TS2591: Cannot find name process` | `tsconfig` 的 `types` 没放 `node`，而 `vite.config.ts` 用了 Node API | `types: ["vite/client", "node"]` + 装 `@types/node`（注意上一条） |
| IDE 报「无法解析自定义属性 `--md-xxx`」 | 这些变量是运行时由 JS 写进根元素的，静态分析看不见 | 忽略；或改用 `var(--md-xxx, 兜底)` |
| 页面整片没样式（元素都是原生外观） | 样式由 `/@vite/client` 动态注入，dev server 重启后旧模块图会失效 | 停掉 dev server、删 `front/node_modules/.vite` 后重启，再硬刷新 |
| 预览区渲染高亮 / 图表重复出现 | 增强函数不幂等（预览会反复跑后处理） | 用 `dataset` 标记或判「父节点已存在」去重 |
| 分享链接复制出来带 `/minidocs` | 站点基址未配对 | 在「站点设置」页填「站点基址」；或用部署兜底 `--minidocs.page-base-url=https://your.host` |
| 页面 404、接口正常 | 静态服务器没配 SPA 回退 | `location / { try_files $uri $uri/ /index.html; }` |
| 外部字体 / 资源被浏览器拦 | CSP 只放行 `'self'` | 在 `SecurityHeaderFilter.DEFAULT_CSP` 显式加域名 |
| SQLite 报文件锁 | 另一个后端进程还开着同一个 `VAULT_HOME` | 停掉旧进程，或换 `VAULT_HOME` |
| 改了 DTO 的 `record` 签名后测试编译不过 | 测试里有直接 `new XxxRequest(...)` 的调用点 | 加一个兼容构造器，别让旧调用点逐个改 |

---

## 10. 文档索引

| 文档 | 内容 |
| --- | --- |
| [README](../README.md) | 平台介绍、快速开始、部署、配置项 |
| 本文 | 本地开发、后端 / 前端约定、测试、排查 |
| [多租户知识库权限与功能规范.md](多租户知识库权限与功能规范.md) | 角色、可见性、维护范围与功能边界 |
| [产品设计需求分析.md](产品设计需求分析.md) | 需求与设计取舍 |
| [开发计划-M5.md](开发计划-M5.md) | 历史开发计划与验收记录 |

