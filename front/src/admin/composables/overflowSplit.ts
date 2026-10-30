/**
 * 「一行放不下就收进『更多』」的切分算法 —— 纯函数，不碰 DOM。
 *
 * <p>单独成一个文件是为了可验证：jsdom 里 {@code offsetWidth} 恒为 0，组件级的测量
 * 测不了，但「怎么切」这段逻辑不该跟着一起变得不可测。量宽那部分在
 * {@link ./useOverflowRow.ts}。</p>
 */

/**
 * 给定各项宽度与可用宽度，算出行内最多能放几项。
 *
 * @param widths    逐项天然宽度（px），顺序即渲染顺序
 * @param available 行内可用宽度（px）
 * @param moreWidth 「更多」按钮宽度（px）
 * @param gap       项间距（px）
 * @returns {@code n}（= 项数）表示全部放得下、不需要「更多」按钮；
 *          {@code k < n} 表示行内放前 k 项，其余进「更多」
 */
export function splitRow(widths: number[], available: number, moreWidth: number, gap: number): number {
  const n = widths.length
  if (n === 0) return 0

  // 前 k 项占的总宽：k 项之和 + (k-1) 个间距
  const span = (k: number) => (k <= 0 ? 0 : widths.slice(0, k).reduce((a, b) => a + b, 0) + gap * (k - 1))

  if (span(n) <= available) return n

  // 放不下时要给「更多」腾位置：它自身 + 它与最后一个可见项之间的间距
  const budget = available - moreWidth - gap
  let k = 0
  // 上限是 n-1：既然全放不下，就必须至少有一项进「更多」，否则「更多」会是个空菜单
  while (k + 1 < n && span(k + 1) <= budget) k++
  return k
}
