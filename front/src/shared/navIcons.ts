/**
 * 导航菜单的图标检索。
 *
 * <p>图标数据在 {@code navGlyphs.generated.ts}（由 {@code scripts/gen-nav-glyphs.mjs} 从
 * Iconify的 Tabler 集生成，MIT）。这里只负责「挑出该展示哪些、怎么排序」。</p>
 *
 * <p>为什么不用 Iconify 的运行时组件：图标是作者运行时挑的，阅读页要按存下来的名字渲染，
 * 于是数据必须随应用一起走；而它的运行时方案要么从 CDN 取（CSP 的script-src 只认
 * 'self'，断网也取不到），要么把整份图标库打进产物。对一个可私有部署的产品，
 * 「构建期烤成TS 文件」是唯一同时满足离线、CSP 与产物体积的方案。</p>
 */
import { GLYPHS, GLYPH_GROUP } from '@/shared/navGlyphs.generated'

export interface NavIconDef {
  /** 存的图标名，也是生成数据的键 */
  name: string
  /** 语义分组，选择器里按它分节 */
  group: string
}

/** 分组展示顺序（与生成脚本里的关键词表一致，从最常用到最冷门） */
export const NAV_ICON_GROUPS = [
  '文档', '分类', '时间', '媒体', '链接', '组织', '数据', '动作', '形态', '工具'
] as const

/** 全量清单，按分组顺序排列 */
export const NAV_ICON_DEFS: NavIconDef[] = Object.keys(GLYPHS).map((name) => ({
  name,
  group: GLYPH_GROUP[name] || '其他'
}))

const byGroup = new Map<string, NavIconDef[]>()
for (const def of NAV_ICON_DEFS) {
  const arr = byGroup.get(def.group)
  if (arr) arr.push(def)
  else byGroup.set(def.group, [def])
}

/** 分组清单：没数据的分组直接不出现，避免留下空标题 */
export const NAV_ICON_SECTIONS: { group: string; items: NavIconDef[] }[] =
  NAV_ICON_GROUPS.map((group) => ({ group, items: byGroup.get(group) || [] })).filter(
    (section) => section.items.length > 0
  )

/**
 * 按关键字过滤。
 *
 * <p>匹配名称里的词片段（Tabler 的命名是 book-open、file-text 这类连字符组合），
 * 所以按 - 切开逐段比对：「书」能命中 books / book-open / address-book。搜中文
 * 也能中——分组名与关键词都在索引里。</p>
 *
 * <p>命中数量截断到 300：一次渲染 900 个 svg 会让弹窗卡住一瞬，
 * 而导航图标没有「排在第 400 位才想到」的用法。</p>
 */
export function searchNavIcons(keyword: string, limit = 300): NavIconDef[] {
  const kw = keyword.trim().toLowerCase()
  if (!kw) return []
  const out: NavIconDef[] = []
  for (const def of NAV_ICON_DEFS) {
    const group = def.group
    if (group && group.includes(keyword.trim())) {
      out.push(def)
      continue
    }
    if (def.name.toLowerCase().includes(kw)) {
      out.push(def)
    }
    if (out.length >= limit) break
  }
  return out
}

/** 菜单项未指定图标时按类型回退：目录用书架、文档用单篇 */
export function navIconOf(icon: string | undefined, type: string | undefined): string {
  return icon || (type === 'dir' ? 'bookshelf' : 'fileText')
}

/** 图标名是否在清单里 —— 后端存下来的值可能是历史遗留或被手改过的 */
export function isNavIcon(key: string | undefined): boolean {
  return !!key && Object.prototype.hasOwnProperty.call(GLYPHS, key)
}
