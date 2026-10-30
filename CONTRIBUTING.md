# 贡献指南

感谢你愿意改进 MiniDocs。这份文档只讲「怎么参与」；本地启动、仓库地图、分层与渲染管线等
开发细节见 [开发指南](docs/DEVELOPMENT.md)。

## 开始之前

- **使用问题**：先查 [使用手册](docs/USAGE.md)；启动、反代、前缀问题查 [部署指南](docs/DEPLOYMENT.md)
- **Bug / 功能建议**：用对应的 Issue 模板，一个 issue 只谈一件事
- **安全漏洞**：不要开公开 issue，按 [SECURITY.md](SECURITY.md) 私下报告
- **参与讨论**：请保持 [行为准则](CODE_OF_CONDUCT.md) 中的基本礼貌

## 开发环境

需要 JDK 17+、Maven 3.9+、Node.js 20+。

```bash
# 终端 A：后端（dev profile + SQLite，开箱即用）
cd backend && mvn spring-boot:run

# 终端 B：前端热更新（http://localhost:5173，接口代理到 :9098）
cd front && npm install && npm run dev
```

默认管理员 `admin / admin123`，数据落在项目根的 `data/`，可用 `VAULT_HOME` 指到别处。
**不要拿真实的 `data/` 目录做实验**：启动时会把知识库目录搬迁到新结构，且会对 SQLite 执行迁移。

## 提交流程

1. Fork 仓库，从 `main` 切出主题分支：`feat/xxx`、`fix/xxx`、`docs/xxx`
2. 一个 PR 只做一件事，避免把格式化、重命名和功能混在一起
3. 提交信息用 `<type>: <中文摘要>`，沿用仓库现有风格：
   `feat` / `fix` / `docs` / `refactor` / `perf` / `test` / `chore`
4. 推送前跑一遍下面的检查清单
5. 按 PR 模板填写改动说明、关联 issue 与自检结果

## 提交前检查

```bash
cd backend && mvn test        # 全量后端测试（约 1 分钟）
cd front && npm run build     # 前端生产构建
```

- 行为有变化时同步更新 `docs/` 下的文档；只改代码不改文档的 PR 会被要求补上
- 涉及 UI 的改动附截图或录屏，并说明验证过的浏览器
- 不要提交密钥、口令、`.env`、`data/`、`.verify/`、`target/`、`dist/` 或任何真实文档内容
- 在 issue / PR 中贴日志前先脱敏（token、Cookie、内网地址、用户名）

## 代码约定

完整约定见 [开发指南 §3「六条硬约定」](docs/DEVELOPMENT.md#3-六条硬约定)。最容易踩的几条：

- **权限判定只写一处**：所有「能不能读 / 写 / 治理 / 分享」都问 `permission/AccessService`，
  业务代码不得自行比较角色或 owner；越权统一按 404 处理，不泄露资源是否存在
- **服务端只输出安全 HTML**：浏览器端交互增强放在 `front/src/shared/enhanceMarkdown.ts`，
  并且每个增强函数必须幂等（预览区会反复调用）
- **渲染缓存键要包含所有影响输出的变量**，否则会出现「改了配置但页面没变」
- **样式改动先读 [DESIGN.md](docs/DESIGN.md)**：视觉层级与色相取舍写在那里，
  代码里只留功能性注释，设计理由统一写进设计文档
- **不引入 Redis、消息队列或分布式锁**：项目的部署约束是单实例，选型时先确认这一点

## 关于新功能

MiniDocs 刻意把权限粒度止步于知识库，文档级 ACL 与实时协同（CRDT）不在范围内；
「组织管理员可编辑本组织所有库」也是刻意设计。提功能前请先读
[权限与功能规范](docs/多租户知识库权限与功能规范.md)，避免重复讨论已经定过的边界。

## License

本项目以 [Apache License 2.0](LICENSE) 发布。提交贡献即表示你同意以同一许可证授权你的贡献
（inbound = outbound），并确认你有权提交这些代码。
