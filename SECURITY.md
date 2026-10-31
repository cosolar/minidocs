# 安全策略

## 支持的版本

| 版本 | 安全更新 |
| --- | --- |
| 1.2.x | ✅ |
| < 1.2 | ❌ |

升级前请先备份 `VAULT_HOME`（数据库、文档与日志都在里面）。

## 报告漏洞

**请不要在公开 issue、讨论区或社交平台披露漏洞细节。**

优先使用 GitHub 的私密报告入口：仓库 **Security → Report a vulnerability**
（Private vulnerability reporting）。若该入口不可用，请通过 GitHub 私信维护者
[@cosolar](https://github.com/cosolar)，并说明你希望如何建立私下沟通渠道。

报告里请尽量包含：

- 受影响的版本与部署方式（Docker Compose / 单 jar / 前后端分离）
- 复现步骤或最小 PoC
- 影响范围（未授权读、越权写、信息泄露、XSS、路径穿越等）
- 你已知的缓解办法（如有）

不要附带真实用户数据、生产环境地址、密钥或 Cookie。我们会尽量在 7 天内确认收到，
修复发布后再公开细节；如果你愿意，可以在发布说明中致谢。

## 测试边界

请只在**你自己部署的实例**上做安全测试。不要对在线演示站进行扫描、爆破、
批量注册、拒绝服务或任何影响其他访客的操作。

## 部署者责任

代码之外的这些配置同样决定安全性，上线前请逐项确认：

- `JWT_SECRET` 必须覆盖为至少 32 字节的随机值，不要沿用内置开发密钥
- `ADMIN_PASS` 默认口令仅用于首次引导，登录后立即修改
- 公网部署启用 HTTPS，并让反向代理传递 `X-Forwarded-*`
  （后端开启 `server.forward-headers-strategy=framework`）
- 按需关闭自助注册（`REGISTER_ENABLED=false`）
- `VAULT_HOME` 只授予运行用户读写权限，并配置可恢复的备份
- 不要把 `data/`、`.env` 或数据库文件放进 Web 根目录或公开仓库

## 代码中的防线

- 权限判定收敛在 `permission/AccessService`，越权统一按 404 响应，不泄露资源存在性
- 路径守卫、上传文件的扩展名 / MIME / 魔数校验
- Markdown 渲染允许作者写原生 HTML（与编辑器预览一致），产物随后经过 jsoup 标签 / 属性
  白名单与 URL 方案白名单：脚本、事件处理器、`javascript:` 与非 `data:image` 的 data URI
  均被剥离；`style` 属性不放行，对齐需求归一成 `md-align-*` 类名
- 分享口令哈希存储，分享 Cookie 使用 HMAC 签名且作用域为根
- 统一安全响应头；CSP 默认只允许 `'self'`，SVG 代理会覆写为更严格的策略
- 审计日志记录敏感操作，但不记录 token 与密码

已知的边界：权限粒度止步于知识库，文档级 ACL 与实时协同不在设计范围内；
组织 OWNER / ADMIN 可编辑本组织所有库是刻意行为，需要绝对私密的内容应放进个人组织。
