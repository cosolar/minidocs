/**
 * 正文阅读宽度（纯浏览器端）。
 *
 * <p>阅读页与分享页的正文默认吃满中栏（宽度由两侧栏的拖拽手柄决定），这里让读者还能再按
 * <b>百分比</b>收窄 —— 中栏被拉得极宽时（4K屏、或者把两侧栏拖到最窄），一行长到上百字并不好读，
 * 收窄后正文居中，段落长度就回到舒服的范围。</p>
 *
 * <p>存在 localStorage 里而不是设置 store：它是「这台屏幕上的偏好」，换设备、换窗口宽度就该
 * 重新选；而且分享页的读者是访客，服务端没有他的账号可写。<b>作用范围只有分享页</b>，与
 * {@code readerFont} 同一口径：面板在分享页，就不该顺手把门户阅读页也改了。</p>
 *
 * <p>与设置 store 那个 {@code contentWidth}（{@code --md-content-width}）是<b>两件事</b>：
 * 那个是站点级的「内容宽度」偏好，管门户卡片等没有侧栏可拖的场景；这里只管正文那一栏，
 * 写成 CSS 变量 {@code --md-doc-width}，由 {@code reader.css} 里的 {@code .md-doc} 消费。</p>
 */

const STORAGE_KEY = 'minidocs.reader-width'

/** 收得太窄就没法读了（技术表格、代码块会挤成竖条），下限取 50%。 */
export const MIN_READER_WIDTH = 50
/** 100% 即「自适应」：占满中栏，与不设上限等价。 */
export const MAX_READER_WIDTH = 100
export const READER_WIDTH_STEP = 5

/** 越界与非法值一律夹回区间（含 localStorage 里被手改过的值） */
export function clampReaderWidth(percent: number): number {
  if (!Number.isFinite(percent)) return MAX_READER_WIDTH
  return Math.min(MAX_READER_WIDTH, Math.max(MIN_READER_WIDTH, Math.round(percent)))
}

export function readReaderWidth(): number {
  try {
    const raw = Number(localStorage.getItem(STORAGE_KEY))
    return raw ? clampReaderWidth(raw) : MAX_READER_WIDTH
  } catch {
    return MAX_READER_WIDTH
  }
}

/**
 * 应用正文宽度：写根元素上的 CSS 变量并记进 localStorage。
 *
 * <p>拖动滑块时每个刻度都会调它，所以只做「写变量 + 写存储」两件轻事，不做节流。</p>
 */
export function applyReaderWidth(percent: number) {
  const value = clampReaderWidth(percent)
  document.documentElement.style.setProperty('--md-doc-width', `${value}%`)
  try {
    localStorage.setItem(STORAGE_KEY, String(value))
  } catch {
    /* 存不下就算了，本次会话内仍然生效 */
  }
}

/** 应用启动时调用一次（与 {@code initReaderFont} 同一时机）。 */
export function initReaderWidth() {
  applyReaderWidth(readReaderWidth())
}
