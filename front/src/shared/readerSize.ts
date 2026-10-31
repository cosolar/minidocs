/**
 * 正文整体字号（纯浏览器端）。
 *
 * <p>与「正文字体」「阅读主题」一样，作用范围是三个宿主：门户阅读页、分享页、后台编辑器预览。
 * 写的是根元素上的 {@code --md-read-size}，{@code markdown.css} 里正文、代码块、表格、
 * 脚注的字号都由它派生 —— 调一处，整篇的层级一起缩放，不会出现「正文变大了、代码还是原样」。</p>
 *
 * <p>为什么不复用 font-size 百分比：浏览器缩放会把顶栏、侧栏一起放大，读者要的往往只是
 * 「字大一点」，不是「整个界面放大」。这里只动正文那棵树。</p>
 *
 * <p>上下限取 13–22px：再小，行内代码与表格在窄屏上会挤成两行；再大，一屏剩不下几行，
 * 长文反而更难读。默认 15.5px 是原样式表写死的值，选它意味着「打开就和不设这个控件时一样」。</p>
 */

const STORAGE_KEY = 'minidocs.reader-size'

export const MIN_READER_SIZE = 13
export const MAX_READER_SIZE = 22
export const READER_SIZE_STEP = 0.5
export const DEFAULT_READER_SIZE = 15.5

/** 夹回区间并按 0.5 对齐（含 localStorage 里被手改过的值） */
export function clampReaderSize(size: number): number {
  if (!Number.isFinite(size)) return DEFAULT_READER_SIZE
  const clamped = Math.min(MAX_READER_SIZE, Math.max(MIN_READER_SIZE, size))
  return Math.round(clamped / READER_SIZE_STEP) * READER_SIZE_STEP
}

export function readReaderSize(): number {
  try {
    const raw = Number(localStorage.getItem(STORAGE_KEY))
    return Number.isFinite(raw) && raw > 0 ? clampReaderSize(raw) : DEFAULT_READER_SIZE
  } catch {
    return DEFAULT_READER_SIZE
  }
}

/** 应用字号：写根元素上的 CSS 变量并记进 localStorage。拖动时每个刻度都会调它。 */
export function applyReaderSize(size: number) {
  const value = clampReaderSize(size)
  document.documentElement.style.setProperty('--md-read-size', `${value}px`)
  try {
    localStorage.setItem(STORAGE_KEY, String(value))
  } catch {
    /* 存不下就算了，本次会话内仍然生效 */
  }
}

/** 应用启动时调用一次（与 {@code initReaderFont} 同一时机）。 */
export function initReaderSize() {
  applyReaderSize(readReaderSize())
}
