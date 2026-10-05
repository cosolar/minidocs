/**
 * 部署前缀：**页面**与**后端**各算各的。
 *
 * <p>两者分开的原因：前端 SPA 由 Nginx（或任何静态服务器）伺服在站点根，页面地址就是
 * {@code https://host/share/{token}}；而后端仍挂在 {@code server.servlet.context-path}
 * 之下，接口是 {@code https://host/minidocs/api/**}。早期把两者合成一个 {@code APP_BASE}，
 * 结果是接口基址只能跟着页面走 —— 页面去了根，接口就变成 {@code /api}，全站 404。</p>
 *
 * <ul>
 *   <li>{@link PAGE_BASE}：页面基址，来自 Vite {@code base}，默认 {@code ""}（站点根）。</li>
 *   <li>{@link BACKEND_BASE}：后端 context-path，来自 {@code VITE_BACKEND_BASE}，默认
 *       {@code /minidocs}。接口基址、以及后端拼出来的地址（头像、封面、正文里的库内链接）都以它为准。</li>
 * </ul>
 *
 * <p>三种地址的拼法别混：走 router 的跳转（{@code router.push}、{@code <router-link>}）由 router 的
 * base 自动处理；页面内链用 {@link appHref}；后端资源（接口文档等）用 {@link appBackendHref}；
 * 后端下发的地址要喂给 router 时先经 {@link stripAppBase} 剥掉后端前缀。</p>
 */

/** 页面基址：{@code ""}（站点根）或 {@code "/minidocs"}（页面也挂在同一前缀下的部署方式）。 */
export const PAGE_BASE = (import.meta.env.BASE_URL || '/').replace(/\/+$/, '')

/**
 * 后端 context-path。
 *
 * <p>构建期可用 {@code VITE_BACKEND_BASE} 覆盖（与后端 {@code CONTEXT_PATH} 对齐）；
 * 不配就是默认的 {@code /minidocs}。改后端前缀时两边一起改，只改一边必然 404。</p>
 */
export const BACKEND_BASE = (import.meta.env.VITE_BACKEND_BASE || '/minidocs')
  .trim()
  .replace(/\/+$/, '')

/** 兼容旧名：页面基址。保留是因为它被当作「站内路径前缀」在十几处使用。 */
export const APP_BASE = PAGE_BASE

/** 给一条前端路由补上页面基址（裸 {@code <a href>}、{@code window.open} 用）。 */
export function appHref(path: string): string {
  if (!path) {
    return PAGE_BASE || '/'
  }
  return PAGE_BASE + (path.startsWith('/') ? path : '/' + path)
}

/**
 * 给一条后端路径补上 context-path。
 *
 * <p>只用于<b>由后端伺服</b>的页面：接口文档（{@code /swagger-ui.html}、{@code /v3/api-docs}）。
 * 页面路由请用 {@link appHref} —— 两者在分离部署下指向不同前缀，混用即 404。</p>
 */
export function appBackendHref(path: string): string {
  if (!path) {
    return BACKEND_BASE || '/'
  }
  return BACKEND_BASE + (path.startsWith('/') ? path : '/' + path)
}

/**
 * 剥掉部署前缀，交给 router 的路径用。
 *
 * <p>后端下发的站内地址（{@code ReadView.docLinkPrefix} / {@code prevLink} / {@code nextLink}、
 * 以及渲染进正文的裸 {@code <a href>}）**都带** context-path —— 它们可能被浏览器直接请求，
 * 少一段就 404。但走 router（{@code router.push}、{@code <router-link>}）时不该再带这一层，
 * 所以先剥后端前缀、再剥页面基址（页面与后端同前缀部署时两者相同，剥一次即可）。</p>
 *
 * <p>判断用 {@code `${前缀}/`} 而不是裸前缀：后者会把 {@code /minidocs-other/...} 这类恰好以
 * {@code /minidocs} 开头的路径也误伤成 {@code -other/...}。已带前缀才剥，没带就原样返回。</p>
 */
export function stripAppBase(path: string): string {
  if (!path) return path
  for (const prefix of [BACKEND_BASE, PAGE_BASE]) {
    if (prefix && path.startsWith(`${prefix}/`)) {
      return path.slice(prefix.length)
    }
  }
  return path
}

/** 接口基地址（axios 的 baseURL）。 */
export const API_BASE = `${BACKEND_BASE}/api`
