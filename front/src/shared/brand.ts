import { PAGE_BASE } from './appBase'

/**
 * 内置站点 Logo（{@code public/logo-mark.png}，从完整横版标志里取出的圆形图标）。
 *
 * <p>走 {@link PAGE_BASE} 而不是写死 {@code /logo-mark.png}：页面挂在子路径下时，
 * 根路径会 404。站点设置里上传过 Logo 时，以服务端下发的 {@code logoSrc} 为准。</p>
 */
export const DEFAULT_LOGO = `${PAGE_BASE}/logo-mark.png`
