<p align="center">
  <img src="docs/images/logo.png" height="150" alt="logo.png" />
</p>

<h1 align="center">MiniDocs 知识库</h1>

MiniDocs 是一款开源知识分享服务平台打造的轻量化、高性能知识库，主打多知识库独立管理、树形文档架构、原生 Markdown 创作、精细化分类标签与权限管控能力。无需复杂部署，即可快速为个人博主、小型团队、工作室搭建专属结构化知识库，完美适配笔记归档、项目文档、教程手册、团队协作资料等各类内容场景，丰富 Halo 平台知识库文档管理相关插件库。


> 轻量、可私有部署的 Markdown 知识库平台 —— 写作、阅读、分享、权限一条线。
> 后端纯 REST，前端单 SPA，数据留在自己的服务器。

MiniDocs 面向「自己维护一批文档、并且要把它分享出去」的团队：内部用多组织与细粒度权限管起来，
对外用一条链接就能读（可加口令与有效期），不需要对方注册账号。

## 特性

**写作**

- Markdown（GFM）编辑与实时预览：表格、任务列表、代码块语法高亮 + 一键复制
- Mermaid 图、KaTeX 公式、Admonition 提示块（`!!! NOTE` / `WARNING` / `TIP` …）
- 目录树与标签、frontmatter 元信息、正文大纲（TOC）与锚点跳转
- 多人协作：编辑锁（60s 续期，超时自动让位）、乐观并发（基线 size/mtime，冲突返回 412）

**阅读**

- 阅读页与分享页共用同一条渲染管线，所见即所得
- 明亮 / 暗黑主题；分享页可切换正文字体（思源黑体、霞鹜文楷、站酷快乐体等）与正文宽度
- 上 / 下一篇导航、站内全文检索（内存倒排索引，命中直达标题）

**分享**

- 整库分享或单篇分享；访问口令、有效期、导航菜单（分享者自选顶栏入口）
- 自定义短链：6–32 位字母数字与 `-_`，全局唯一（跨组织查重），可随时更换
- 访问量按「访问会话」计数（30 分钟内同一访客只算一次），独立访客按天去重
- 链接可撤销；换短链会二次确认，旧链接立即失效

**组织与权限**

- 多租户（组织），注册即自动建一个个人组织
- 角色 OWNER / EDITOR / VIEWER；库可见性 public / org / private；维护范围 owner_only / members / org_all
- 维护名单、入组申请、操作审计（留痕但不记录 token 与密码）
- 平台治理：用户管理、站点名称与 Logo

**工程**

- 单 SPA 一次构建；页面部署在站点根，接口走独立前缀，前后端可分离部署
- 严格 CSP 与安全响应头；分享 Cookie HMAC 签名、作用域为根；越权统一按 404 处理（不泄露存在性）
- SQLite / MySQL 双方言，内容迁移脚本随 jar 启动自动执行

## 快速开始

前置：**JDK 17+**、**Maven 3.9+**、**Node.js 20+**

```bash
# 1. 构建前端（产物：front/dist）
cd front
npm install
npm run build

# 2. 启动后端（dev profile + SQLite，开箱即用，无需额外配置）
cd ../backend
mvn spring-boot:run
```

打开 <http://localhost:9098> —— 页面在根，接口在 `/minidocs/api`。

- 默认管理员：**admin / admin123**（由 `ADMIN_USER` / `ADMIN_PASS` 初始化），**首次登录后请立刻改密码**
- 数据默认落在 `./data`（可用 `VAULT_HOME` 改到独立目录）

> 若环境变量 `NODE_ENV=production`，`npm install` 会跳过 devDependencies，请改用 `npm install --include=dev`。

前端热更新开发：

```bash
cd front && npm run dev    # http://localhost:5173，接口自动代理到 :9098
```

## 部署

### 前后端分离（推荐）

前端产物交给任意静态服务器，后端只提供接口。

```bash
cd front && npm run build
cd ../backend && mvn clean package -DskipTests

java -jar target/minidocs.jar \
  --spring.profiles.active=prod \
  --minidocs.vault-home=/data/minidocs \
  --minidocs.page-base-url=https://kb.example.com \
  --spring.datasource.url="jdbc:mysql://db:3306/minidocs?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false" \
  --spring.datasource.username=minidocs \
  --spring.datasource.password=****** \
  --jwt-secret=please-generate-a-32-byte-secret
```

Nginx 关键两段——静态资源 + SPA 深链接回退，以及接口反代：

```nginx
root /srv/minidocs/dist;

location / { try_files $uri $uri/ /index.html; }     # SPA 深链接（/share/xxx、/console/…）
location /minidocs/ { proxy_pass http://127.0.0.1:9098; }
```

- `--minidocs.page-base-url` 是分享链接的基址，**分离部署必配**，否则复制出去的链接会带上接口前缀而打不开
- 置于反向代理之后时建议开启 `server.forward-headers-strategy=framework`，让后端正确识别 `X-Forwarded-*`

### 单体部署（只分发一个 jar）

让后端同时伺服页面：构建前端时把接口前缀置空，运行时指向前端产物目录。

```bash
cd front && VITE_BACKEND_BASE= npm run build

java -jar target/minidocs.jar \
  --server.servlet.context-path= \
  --minidocs.front-dir=/srv/minidocs/dist ...
```

两种形态不要混着配：接口前缀、页面基址、分享链接基址三者需保持一致。

## 使用指南

**写一篇文档**

支持 GFM 表格与任务列表、代码块、引用、`!!! NOTE` 类提示块、`mermaid` 图、`$$` 公式；
文档顶部可选写 frontmatter（标题、标签等），目录结构与左侧文档树一一对应。

**分享出去**

在知识库或工作区里点「分享」→ 选范围（整库 / 单篇）→ 可设访问口令与有效期 → 勾选导航菜单 → 生成链接。
短链留空自动生成，填了就是自己定的那条；已有链接也能改（会先确认「旧链接立即失效」）。

**权限模型**

| 维度 | 取值 | 管的是 |
| --- | --- | --- |
| 库可见性 | `public` / `org` / `private` | 谁能看到这个库 |
| 维护范围 | `owner_only` / `members` / `org_all` | 谁算这个库的维护者 |
| 成员角色 | `OWNER` / `EDITOR` / `VIEWER` | 读 / 写 / 治理 / 分享 四条轴的判定 |

细则见 [`docs/多租户知识库权限与功能规范.md`](docs/多租户知识库权限与功能规范.md)。

## 架构

```
minidocs/
├── backend/                Spring Boot 3 · MyBatis-Plus · Sa-Token · flexmark
│   └── src/main/java/cn/minims/minidocs
│       ├── reader/         渲染与阅读视图（门户 / 分享共用一条管线）
│       ├── kb/ · doc/      知识库与文档
│       ├── share/          分享、口令、访问统计
│       ├── tenant/ · permission/ · audit/   组织、权限裁决、审计
│       └── common/         路径守卫、异常、Web 配置、安全响应头
├── front/                  Vue 3 · Vite · Pinia · Element Plus（管理端按需）
│   └── src
│       ├── user/           门户 / 阅读 / 分享
│       ├── admin/          登录注册 / 组织管理 / 平台治理
│       └── shared/         请求层、设置 store、设计变量、Markdown 渲染
└── docs/                   产品与权限规范、开发计划
```

技术要点：

- **一次渲染两处用**：渲染缓存键 `kbId + path + mtime + size`，门户与分享复用同一份 HTML
- **零外部依赖的全文检索**：内存倒排索引（上限约 2000 篇），命中直接给出标题链接
- **目录树缓存**按 `kbId + 目录 mtime`，语义等价且失效判断更稳
- **管理端按需加载**：Element Plus 只在进入 `/console/**`、`/login`、`/platform/**` 时动态载入，
  门户与阅读页的产物里没有组件库

## 开发

```bash
cd backend && mvn test        # 后端测试
cd front  && npm run dev      # 前端热更新
cd front  && npm run build    # 产出 front/dist
```

## 配置项

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `9098` | HTTP 端口 |
| `CONTEXT_PATH` | `/minidocs` | 后端 context-path，即**接口**前缀（页面不带这一层） |
| `PAGE_BASE_URL` | 空 | 站点基址，分享链接的唯一来源；留空按当前请求推导。分离部署必填 |
| `VAULT_HOME` | `./data` | 数据根目录（SQLite、文档、日志），**建议生产写绝对路径** |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev`=SQLite，`prod`=MySQL |
| `DB_HOST` / `DB_USER` / `DB_PASSWORD` / `DB_URL` | — | MySQL 连接信息 |
| `JWT_SECRET` | 内置开发密钥 | **生产必须覆盖**，≥ 32 字节 |
| `ADMIN_USER` / `ADMIN_PASS` | `admin` / `admin123` | 内置管理员 |
| `REGISTER_ENABLED` | `true` | 自助注册开关；内网部署建议置 `false` |
| `SHARE_COOKIE_HOURS` | `12` | 分享口令通过后的免密时长 |
| `FRONT_DIR` | 空 | 仅「后端兼作静态服务器」时使用，指向前端产物目录 |
| `LOG_LEVEL` | `info` | 日志级别 |

其余业务阈值见 `application.yml` 的 `minidocs.*`（文档 2MB、图片 20MB、封面上限、层级 6 层、导入上限等）。

## 贡献

欢迎 issue 与 PR。对齐规范与既有设计后再动手，有两条约定值得先知道：

- 权限判定只写在 `permission/AccessService` 一处，业务代码不得自行比较角色或 owner（测试里有静态守卫）
- 改动分享、渲染、目录树等共享逻辑时，同步更新 `docs/` 下的规范文档

## License

⚠️ 仓库暂未包含 LICENSE 文件。正式对外发布前请补上（例如 MIT 或 Apache-2.0），并在上面替换本节说明。
