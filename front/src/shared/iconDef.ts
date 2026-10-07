/**
 * 图标的两种形态：描边与填充。
 *
 * <p>项目里的图标绝大多数是 24×24 线条（{@code stroke=currentColor}），
 * 但有些现成的图标（尤其来自 iconfont / Iconify 的导出）自带 1024×1024 的
 * {@code viewBox} 且靠 {@code fill} 上色。硬塞进同一个 {@code <svg>} 会出两个问题：</p>
 *
 * <ul>
 *   <li>描边会沿着填充图形的轮廓再描一遍，15px 下就是一团糊；</li>
 *   <li>两条路径一条画框一条画标，重叠处会被上一条的 {@code stroke} 压出接缝。</li>
 * </ul>
 *
 * <p>所以形态必须由定义自己声明，组件只负责按声明给属性 —— 不在图标文件里
 * 凭 path 数量去猜「这个是不是填充的」，那种推断迟早会错。</p>
 */

/** 线条图标的统一坐标系。所有线条图标都在这个 viewBox 里。 */
export const STROKE_VIEWBOX = '0 0 24 24'

/** 填充型图标：自带 viewBox，靠 fill 上色。 */
export interface SolidIcon {
  viewBox: string
  paths: string[]
}

/** 归一化后的图标定义，组件直接照它给svg 属性。 */
export interface IconDef {
  paths: string[]
  viewBox: string
  /** true = 填充型（fill=currentColor、stroke=none）；false = 线条型 */
  filled: boolean
}

/**
 * Markdown 文件：文件夹轮廓里嵌 GitHub 标记。
 *
 * <p>「Markdown 文件」在 UI 上没有专属的抽象图形，行业里约定俗成的做法就是用 GitHub
 * 标记（Octicons 的 mark-github）—— 代码托管平台与 Markdown 渲染器都用它，
 * 用户不需要学就知道点开是什么。</p>
 *
 * <p>刻意<b>不</b>覆盖线条版的 {@code file}：那个还用在「下载 Markdown」这类菜单项与
 * 卡片元信息上，菜单里放一个大块实心标记会喧宾夺主，而菜单项需要的是「这是一份文件」
 * 而不是「这是一个 Markdown」。</p>
 */
const MARKDOWN: SolidIcon = {
  viewBox: '0 0 1024 1024',
  paths: [
    // 内层：分支端子 + 外框 + GitHub 标记
    'M877.583 591.758V521.78c0-16.448-13.334-29.781-29.781-29.781s-29.78 13.333-29.78 29.78v69.978H772.16'
      + 'a4.243 4.243 0 0 0-3.262 6.956l75.803 91.137a4.243 4.243 0 0 0 6.524 0l75.803-91.137a4.243 4.243'
      + ' 0 0 0-3.262-6.956h-46.184zM931.6 320c33.3 0 60.4 27.1 60.3 60.4v423.1c0 33.3-27 60.4-60.4 60.4'
      + 'H508.4c-33.3 0-60.4-27-60.4-60.4V380.4c0-33.3 27-60.4 60.4-60.4h423.2zM638.913 677.19l38.912-97.152'
      + 'V692h52.597V492h-63.925l-45.222 114.492L575.923 492H512v200h52.598V580l39.048 97.19h35.267z',
    // 外层：文件夹轮廓
    'M764 952H104V327h220c19.9 0 36-16.1 36-36V72h368v148c0 19.9 16.1 36 36 36s36-16.1 36-36V72'
      + 'c0-39.8-32.2-72-72-72H317.8c-19.1 0-37.4 7.6-50.9 21.1L53.1 234.9A71.983 71.983 0 0 0 32 285.8V952'
      + 'c0 39.8 32.2 72 72 72h660c19.9 0 36-16.1 36-36s-16.1-36-36-36zM288 101.8V255H134.8L288 101.8z'
  ]
}

/**
 * 填充型图标注册表。键与各组件的线条图标表共用同一套命名，查找时它优先 ——
 * 所以同名即覆盖，不需要额外的「别名」概念。
 */
export const SOLID_ICONS: Record<string, SolidIcon> = {
  markdown: MARKDOWN
}

/**
 * 归一化一个图标名。
 *
 * @param stroke   该组件自己的线条图标表（两套组件各有各的图标集，不共用）
 * @param name     图标名
 * @param fallback 线条表里查不到时退回的键；组件各自的默认图标不一样
 */
export function resolveIcon(
  stroke: Record<string, string[]>,
  name: string,
  fallback = 'file'
): IconDef {
  const solid = SOLID_ICONS[name]
  if (solid) {
    return { paths: solid.paths, viewBox: solid.viewBox, filled: true }
  }
  return {
    paths: stroke[name] || stroke[fallback] || [],
    viewBox: STROKE_VIEWBOX,
    filled: false
  }
}