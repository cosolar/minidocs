# 阅读主题包

正文的配色与排版由**主题包**决定。主题包是一份 JSON：只描述设计令牌（CSS 自定义属性）的取值，
不包含选择器、脚本或任意 CSS 规则。管理员在「站点设置 → 阅读主题」里上传 / 卸载，
读者在分享页与门户阅读条的「阅读设置」里选用，后台编辑器预览跟随同一套。

```
读者偏好                            主题包（管理员安装）
├── 主题 id ────────────────┐
├── 外观（明 / 暗 / 跟随）──┤
├── 字号 ──────────────────┤──> <style> 写到页面 ──> 三处宿主
├── 正文字体 ──────────────┤
└── 正文宽度（仅分享页）───┘
```

**四类设置互相独立**：主题只给颜色与排版参数；外观决定取 `light` 还是 `dark` 那一档；
字号 / 字体 / 宽度是读者自己的偏好，主题包**不允许**覆盖（见下面的保留令牌）。

---

## 1. 内置主题

| id | 名称 | 风格 |
| --- | --- | --- |
| `default` | 澄澈 | MiniDocs 默认：雾蓝底、蓝青强调。**不落 JSON**，色值由 `variables.css` 提供，不可卸载 |
| `gatsby` | 紫笺 | 暖纸底、衬线标题与深紫强调（参考 Typora Gatsby） |
| `newsprint` | 铅字 | 米白纸面、全衬线排版与黑白强调，像一份旧报纸 |
| `nord` | 北境 | 极地蓝灰与雾青点缀，低饱和（参考 Nord 配色） |
| `solarized` | 暖沙 | 米黄纸面配青蓝墨色（参考 Solarized 配色） |
| `vitepress` | 青提 | 纯白底、克制绿色强调与方形标题（参考 VitePress 文档站） |

内置包在 `backend/src/main/resources/themes/*.json`。应用首次启动时复制到
`{VAULT_HOME}/site/themes/`，并写一个 `.seed-<id>` 标记：

- 管理员卸载某套内置主题 → 只删 `.json`，**保留标记**，下次启动不会「删了又回来」；
- 想找回 → 「恢复内置主题」（`reseed`），这一层忽略标记、强制覆盖写回；
- 管理员自己放进去的同名包不会被启动种子覆盖，只补标记。

---

## 2. 主题包格式

最小可用结构如下（完整模板可在「站点设置 → 阅读主题 → 下载模板」取得，
源码在 `front/src/shared/themeTemplate.ts`）：

```json
{
  "id": "my-theme",
  "name": "我的主题",
  "description": "一句话描述，会显示在读者的主题卡片下方",
  "author": "你的名字",
  "version": "1.0.0",
  "license": "MIT",
  "light": {
    "--md-surface": "#ffffff",
    "--md-text": "#1f2333",
    "--md-primary": "#2563eb",
    "--md-accent": "#14b8a6",
    "--md-ink-blue": "#1d4ed8",
    "--md-ink-teal": "#0f766e"
  },
  "dark": {
    "--md-surface": "#161922",
    "--md-text": "#e7e9f2",
    "--md-primary": "#6c8cff",
    "--md-accent": "#2dd4bf",
    "--md-ink-blue": "#93b4ff",
    "--md-ink-teal": "#5eead4"
  }
}
```

字段约束：

| 字段 | 规则 |
| --- | --- |
| `id` | 小写字母 / 数字 / 连字符，2–40 位；`default` 是保留 id |
| `name` | 必填，≤ 40 字 |
| `description` / `author` / `version` / `license` | 可选，分别 ≤ 200 / 40 / 20 / 30 字 |
| `light` / `dark` | 必填，**两档必须定义同一组令牌**，缺一条整包拒绝 |
| 单档令牌数 | ≤ 160 |
| 单个令牌值 | ≤ 240 字符 |
| 文件大小 | ≤ 64KB |

> 只写很少的令牌也能装上，但没写的部分会回落到默认主题。
> 「下载模板」给出的是一份**装上就有效果**的完整包：核心色 + 代码高亮 + 结构令牌都填好了默认值。

---

## 3. 令牌分组

### 3.1 核心颜色（每套主题都该覆盖）

| 令牌 | 用途 |
| --- | --- |
| `--md-bg` / `--md-surface` / `--md-surface-2` | 页面底、卡片面、次级面 |
| `--md-sidebar` | 侧栏底 |
| `--md-text` / `--md-text-2` / `--md-text-3` | 正文 / 次要 / 弱化文字 |
| `--md-border` / `--md-border-strong` | 发丝线 / 强分隔线 |
| `--md-primary` / `--md-primary-hover` / `--md-primary-soft` | 主色与悬停、淡底 |
| `--md-accent` | 次强调（链接下划线、列表标记等） |
| `--md-ink-blue` / `teal` / `violet` / `amber` / `rose` / `green` | 正文语义色。**正文里的彩色文字都走这一组**，不要用 `--md-primary` 充当正文色（小面积按钮色压白底对比度不够） |
| `--md-mark-bg` | `==高亮==` 的底色 |
| `--md-code-bg` / `--md-code-text` / `--md-code-border` | 代码块底色 / 默认字色 / 边框 |

### 3.2 代码高亮（`--md-tk-*`，9 个）

`keyword` / `string` / `comment` / `number` / `title` / `attr` / `builtin` / `type` / `meta`。

这组令牌**只在「代码块主题 = 跟随阅读主题」时生效**。读者在账号设置里显式选了
GitHub / Monokai 等代码主题时，那套配色优先，主题包不会把它改掉（实现见
`readerTheme.ts` 里的 `[data-code-theme='theme']` 前缀选择器）。

写主题包时**九个别只写一部分**：漏掉的会回落到默认浅色值，压在深色代码底上几乎读不出来。

### 3.3 结构令牌（`--md-theme-*`）

标题字号与间距、H1 渐变线、H2 左侧标记、引用条、表格头、代码块圆角、脚注、图片圆角等。
完整清单见 `themeTemplate.ts` 的 `STRUCTURE`，消费方在 `markdown.css`。

结构令牌默认值**同时包含明暗两档**（例如 `--md-theme-quote-bg` 用
`color-mix(... var(--md-ink-blue) ...)` 派生），所以通常两档填同一份即可；
需要分别调时再在 `light` / `dark` 里给不同值。

### 3.4 保留令牌（写入即拒）

| 令牌 | 原因 |
| --- | --- |
| `--md-read-font` | 读者的正文字体偏好 |
| `--md-read-scale` / `--md-read-size` | 读者的字号偏好 |
| `--md-doc-width` / `--md-content-width` | 正文栏宽 |
| `--md-aside-width` / `--md-outline-width` | 阅读页骨架尺寸 |
| `--md-topbar-height` | 顶栏高度（三栏 sticky 偏移依赖它） |

放行它们会让「界面上有控件、实际由主题说了算」，比没有控件更糟。

---

## 4. 作用范围

主题令牌写到两处选择器：

| 选择器 | 影响 |
| --- | --- |
| `body.md-page--read` | 门户阅读页、分享页**整页**（顶栏、侧栏一起换肤） |
| `.md-ws__editor .markdown-body` | 后台编辑器的**正文预览**，工具栏保持管理端配色 |

暗色令牌包在 `[data-theme='dark']` 下，与现有的明亮 / 暗黑 / 跟随系统切换正交。

读者偏好存在 `localStorage`：

| 键 | 内容 |
| --- | --- |
| `minidocs.reader-theme` | 选中的主题 id |
| `minidocs.reader-themes` | 主题列表缓存（断网 / 后端未起时仍可切换） |
| `minidocs.reader-size` | 正文字号（13–22px，步长 0.5） |

管理员卸载读者正在使用的主题后，读者下次打开页面会自动回落到「澄澈」。

---

## 5. 安装与卸载

### 界面

「站点设置 → 阅读主题」：上传 `.json`、卸载自定义或内置主题、恢复内置主题、下载模板。
只有平台管理员能进入（`/api/platform/**`），因为主题影响整个部署的阅读版面。

### 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/portal/themes` | 公开：已安装主题列表 |
| `GET` | `/api/platform/site/themes` | 平台管理员：同列表（含 `builtin` 标记） |
| `POST` | `/api/platform/site/themes` | `multipart/form-data`，字段名 `file` |
| `DELETE` | `/api/platform/site/themes/{id}` | 卸载；`default` 永远拒绝 |
| `POST` | `/api/platform/site/themes/reseed` | 恢复内置主题（覆盖写回） |

### 磁盘

```
{VAULT_HOME}/site/themes/
├── gatsby.json
├── nord.json
├── .seed-gatsby          ← 内置种子标记，卸载后保留
└── ...
```

写入采用「临时文件 + 原子替换」，读侧不会看到写了一半的 JSON。
列表读取时跳过坏文件与超限文件，不会因为一个包坏了就让全部主题消失。

---

## 6. 安全边界

主题包不是 CSS 上传。它只能改令牌值，不能新增选择器，因此：

- 能改的只有颜色、字体栈、渐变、尺寸这类**属性值**；
- 值里禁止出现 `url()`、`image-set()`、`expression()`、`javascript:`、`@import`、
  `behavior:`、`-moz-binding`、`!important`，以及 `; { } < > \` 与控制字符 ——
  这些足以逃出声明块或发起外部请求；
- 前后端各校验一遍：后端 `ThemeServiceImpl`（安装与读取时）、前端 `readerTheme.ts`
  （拿到的列表再筛一次）。绕过界面直接调接口，也过不了后端那一关。

正常值包含 `color-mix(in srgb, ...)`、`linear-gradient(...)`、`var(--md-ink-blue)`，
都不会命中禁用规则。

---

## 7. 加一个主题令牌

三处必须一起改，缺一处就会出现「主题里设了但页面没反应」或「暗色下回落到默认值」：

1. **在 `variables.css` 的阅读宿主上给默认值**：

   ```css
   body.md-page--read,
   .md-ws__editor .markdown-body {
     --md-theme-xxx: ...;
   }
   ```

   **不要写在 `:root`**。自定义属性里的 `var()` 是在**声明它的元素**上完成替换的：
   写在 `:root` 会把默认主题的色值冻结在根元素，主题包后来覆盖 `--md-ink-blue` 也影响不到它。

2. **在 `markdown.css` 里消费**：`property: var(--md-theme-xxx, 兜底值)`。
3. **在 `themeTemplate.ts` 的 `STRUCTURE` 里补一行**，下载模板才能带上它。

改完必须重新构建前端，并在构建产物里确认字符串真的进去了（本项目多次出现「源码改了但没进产物」）。

---

## 8. 验证

```bash
# 主题包结构：JSON 合法、id/文件名一致、明暗键集相同、无保留令牌、无危险取值
node .verify/_themecheck.mjs

# 后端主题服务：种子 / 卸载不复活 / reseed / 安装校验
cd backend && mvn -o -Dtest=ThemePackStorageTest test

# 前端构建
cd front && npm run build
```

浏览器里要覆盖：

- 分享页、门户阅读页、后台编辑器预览三处换主题，正文与代码块同步变；
- 明亮 / 暗黑两档都试，切换外观后主题仍然生效；
- 字号滑杆拉动时，正文、代码块、表格、脚注一起缩放；
- 账号设置里显式选 GitHub / Monokai 后，再换阅读主题，代码配色**不跟着变**；
- 管理员安装 / 卸载 / 恢复内置主题后，读者刷新页面列表跟着变。
