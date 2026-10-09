/**
 * 正文字体偏好（纯浏览器端）。
 *
 * <p>分享页给读者一个「换字体」的选择，存在 localStorage 里，不入库、不入设置中心：访客没有
 * 身份，而这类偏好跟着的是「这台设备上的这台浏览器」，换设备就该重新选一次。</p>
 *
 * <p><b>作用范围只有分享页</b>：设置面板在分享页顶栏，变量虽然写在根元素上，消费方却只认
 * {@code .md-reading[data-mode='share'] .md-markdown}。门户阅读页共用 {@code .md-markdown}，
 * 早先在通用规则里读这个变量，结果分享页一换字体、阅读页也跟着变，而那边没有入口能改回来。</p>
 *
 * <p><b>为什么只有字体走这里、主题不走：</b>主题已有 {@code shared/stores/settings.ts} 那份
 * 单一来源（{@code themeMode} + {@code applyToDom}），再造一个就会和应用设置、后台设置页三处
 * 互相覆盖；访客页面改主题时用 store 上的 {@code patchLocal}，只本地持久化、不发网关。</p>
 *
 * <p><b>关于在线字体：</b>除「默认系统字体」外，各款 webfont 都走 jsDelivr 的国内镜像
 * {@code cdn.jsdmirror.com}（只有那边有按 unicode-range 分片的版本，一篇正文命中几十片、每片几十 KB，
 * 比整包下载省得多），由 {@code hrefs} 声明、选中时才注入样式表。
 * 注入需要后端 CSP 放行该域名：{@code SecurityHeaderFilter.DEFAULT_CSP} 的
 * {@code style-src} / {@code font-src} 已含 {@code cdn.jsdmirror.com}（{@code script-src} 仍只认
 * 'self'，CDN 上的脚本执行不了）。字体栈里同时写了本地安装名 —— 本地装了就直接命中，连网络都不用。</p>
 *
 * <p>选品标准：可商用的开源字体（SIL OFL），且优先「全字符集」的 —— 只覆盖常用七千字的美术字体
 * 拿来做正文，生僻字会悄悄回退到系统字体，一屏里两种字面混排反而更难读。这类只作艺术字选项，
 * 面板里会写清它的字符集限制。</p>
 *
 * <p>要彻底不依赖外部域名：把字体分片下载到 {@code front/public/fonts/}（构建时原样拷进产物），
 * 改这里的 {@code hrefs} 指向自托管路径，再把后端 CSP 里那个域名去掉。</p>
 */

const STORAGE_KEY = 'minidocs.reader-font'

export interface ReaderFont {
  id: string
  label: string
  /** CSS font-family 栈：本地安装名在前，webfont 名居中，系统字体兜底 */
  stack: string
  /** 面板里的一行说明：这个字体在什么条件下才会真的生效 */
  note: string
  /**
   * 在线字体样式表地址（可多张：例如 400 与 700 分开提供）。
   *
   * <p>留空表示只用本地字体；填了则选中该字体时自动注入。注入需要后端 CSP 放行样式表域名，
   * 见文件头说明。</p>
   */
  hrefs?: readonly string[]
}

const SANS =
  "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', Arial, sans-serif"

export const READER_FONTS: readonly ReaderFont[] = [
  {
    id: 'system',
    label: '默认系统字体',
    stack: SANS,
    note: '与门户、后台同一套系统字体栈，跟随操作系统的中文字体设置。'
  },
  {
    id: 'source-han',
    label: '思源黑体',
    // 思源黑体的家族名在不同系统上三种叫法都出现过，挨个兜住
    stack: "'Noto Sans SC', 'Source Han Sans SC', 'Source Han Sans CN', '思源黑体', " + SANS,
    note: '思源黑体 / Noto Sans SC，屏显黑体，字重齐全。',
    hrefs: [
      'https://cdn.jsdmirror.com/npm/@fontsource/noto-sans-sc@5.0.18/400.css',
      'https://cdn.jsdmirror.com/npm/@fontsource/noto-sans-sc@5.0.18/700.css'
    ]
  },
  {
    id: 'noto-serif-sc',
    label: '思源宋体',
    stack: "'Noto Serif SC', 'Source Han Serif SC', 'Source Han Serif CN', '思源宋体', " + SANS,
    note: '思源宋体 / Noto Serif SC，宋体，适合长文与文档正文。',
    hrefs: [
      'https://cdn.jsdmirror.com/npm/@fontsource/noto-serif-sc@5.3.0/400.css',
      'https://cdn.jsdmirror.com/npm/@fontsource/noto-serif-sc@5.3.0/700.css'
    ]
  },
  {
    id: 'lxgw-wenkai',
    label: '霞鹜文楷',
    stack: "'LXGW WenKai', 'LXGW WenKai Screen', '霞鹜文楷', " + SANS,
    note: '霞鹜文楷 / LXGW WenKai，楷体，适合技术文章与散文。',
    hrefs: [
      'https://cdn.jsdmirror.com/npm/lxgw-wenkai-webfont@1.7.0/lxgwwenkai-regular.css',
      'https://cdn.jsdmirror.com/npm/lxgw-wenkai-webfont@1.7.0/lxgwwenkai-bold.css'
    ]
  },
  {
    id: 'zcool-kuaile',
    label: '站酷快乐体',
    stack: "'ZCOOL KuaiLe', '站酷快乐体', " + SANS,
    note: '站酷快乐体 / ZCOOL KuaiLe，可用于标题与短句；它的字符集只有约 7000 字，生僻字会回退到系统字体。',
    hrefs: ['https://cdn.jsdmirror.com/npm/@fontsource/zcool-kuaile@5.3.0/400.css']
  }
]

export const DEFAULT_READER_FONT = 'system'

/** 按 id 取字体项；不认识的值（含旧版本残留）退回默认 */
export function readerFontById(id: string | null | undefined): ReaderFont {
  return READER_FONTS.find((font) => font.id === id) || READER_FONTS[0]
}

/** 当前偏好：只认字体表里存在的 id */
export function readReaderFont(): string {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? readerFontById(raw).id : DEFAULT_READER_FONT
  } catch {
    // 隐私模式等场景下 localStorage 会抛错；读不到就用默认值，不影响阅读
    return DEFAULT_READER_FONT
  }
}

/**
 * 把字体样式表挂到页面上（同一地址只挂一次）。
 *
 * <p>浏览器自带缓存，第二次选同一款字体不会重复请求；样式表里的字体文件是相对路径，仍落在
 * 同一个 CDN 域名下，{@code font-src} 放行一处就够。加载失败不阻断页面，字体栈会安静地落到
 * 栈尾的系统字体。</p>
 */
function mountWebfonts(hrefs: readonly string[]) {
  for (const href of hrefs) {
    if (document.querySelector(`link[data-reader-font-css="${href}"]`)) continue
    const link = document.createElement('link')
    link.rel = 'stylesheet'
    link.href = href
    link.dataset.readerFontCss = href
    document.head.appendChild(link)
  }
}

/**
 * 只把某款字体的在线样式表挂上，<b>不改</b>当前偏好、不写 localStorage。
 *
 * <p>给「固定用某款字体的界面元素」用：分享页顶栏的导航菜单就固定思源黑体，
 * 不跟读者的正文字体偏好走，但它仍需要 webfont 真的加载上 ——
 * 只写 {@code font-family} 而不注入样式表，浏览器找不到本地安装的思源黑体时
 * 会安静地落到系统字体，界面上看不出任何异常，只是「没生效」。</p>
 *
 * <p>与 {@link applyReaderFont} 分开是刻意的：那个会改读者偏好，
 * 这里只是让某个元素用上字体。共用一个函数的话，加载导航字体时会顺手
 * 把读者选的正文字体也改掉。</p>
 */
export function ensureReaderFontWebfonts(id: string) {
  const font = readerFontById(id);
  if (font.hrefs?.length) mountWebfonts(font.hrefs);
}

/**
 * 应用字体偏好：写根元素上的 CSS 变量，必要时挂在线字体，并记进 localStorage。
 *
 * <p>写成变量而不是直接给正文加 class：一份 CSS 变量就能同时作用于分享页与门户阅读页的正文，
 * 样式表那边只要读 {@code var(--md-read-font)}。</p>
 *
 * <p>只换字体、不动行高段距：行高一改，整篇的块高（引用块、表格、代码块）都会跟着变，
 * 读者换字体时看到的是「版面被撑开了」而不是「字变了」，代价比字形差异大得多。</p>
 */
export function applyReaderFont(id: string) {
  const font = readerFontById(id)
  document.documentElement.style.setProperty('--md-read-font', font.stack)
  if (font.hrefs?.length) mountWebfonts(font.hrefs)
  try {
    localStorage.setItem(STORAGE_KEY, font.id)
  } catch {
    /* 存不下就算了，本次会话内仍然生效 */
  }
}

/**
 * 应用启动时调用：把上次选的字体重新套上。
 *
 * <p>由阅读侧入口（{@code main.ts}）调用一次即可 —— 变量挂在根元素上，之后切路由、进出分享页
 * 都不必再管。</p>
 */
export function initReaderFont() {
  applyReaderFont(readReaderFont())
}
