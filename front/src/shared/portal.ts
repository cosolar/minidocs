/**
 * 门户阅读页地址。
 *
 * <p>v2 起 slug 只在组织内唯一，URL 必须带组织段（规范 §4.2 / §7.1.3）。缺组织信息时退回单段老
 * 地址：后端对唯一命中会 301，撞名时由消歧页接管，比在前端静默猜一个组织要诚实。</p>
 *
 * <p>名字带 {@code portal} 前缀：管理侧的 {@code api/context.ts} 里已有一个 {@code kbUrl}，拼的是
 * {@code /api/console/{org}/kbs/...} 的后端地址，两侧合并成单 SPA 后同名会互相顶掉。</p>
 */
export function portalKbUrl(orgSlug: string | undefined, slug: string, path?: string): string {
  const encoded = encodeURIComponent(slug)
  const base = orgSlug ? `/kb/${encodeURIComponent(orgSlug)}/${encoded}` : `/kb/${encoded}`
  return path ? `${base}?path=${encodeURIComponent(path)}` : base
}
