import { PAGE_BASE } from './appBase'

/**
 * 内置站点 Logo（{@code public/logo-mark.png}，从完整横版标志里取出的圆形图标）。
 *
 * <p>走 {@link PAGE_BASE} 而不是写死 {@code /logo-mark.png}：页面挂在子路径下时，
 * 根路径会 404。站点设置里上传过 Logo 时，以服务端下发的 {@code logoSrc} 为准。</p>
 */
export const DEFAULT_LOGO = `${PAGE_BASE}/logo-mark.png`

/**
 * 内置浏览器标签页图标（{@code public/favicon.png}，128px 的圆形标记）。
 *
 * <p>站点设置里传了「站点图标」时以服务端下发的 {@code iconSrc} 为准；没传就回到这一份，
 * 不跟随横版 Logo —— 组合图压进 16px 的标签页只剩一条色带。</p>
 */
export const DEFAULT_FAVICON = `${PAGE_BASE}/favicon.png`
