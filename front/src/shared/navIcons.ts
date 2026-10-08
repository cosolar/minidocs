/**
 * 分享页导航菜单可自选的图标。
 *
 * <p>选择器与渲染器共用这一份清单：清单写两遍的话，某天加了一个图标却忘了同步到
 * 另一处，菜单上就会出现一个点不亮的格子 —— 那类问题只有手动点一遍才发现得了。</p>
 *
 * <p><b>键必须取自用户侧 {@code Icon.vue} 已有的名字</b>（外加 shared/iconDef.ts 里注册的
 * {@code bookshelf} / {@code back}）。写错名字不会报错，只会安静地回退成文件图标 ——
 * 所以加新项时先确认那边有：{@code grep '^  [a-zA-Z]+:' Icon.vue}。</p>
 *
 * <p>挑的是「能表达一个入口类型」的词（书、单篇、清单、标签、时钟、链接……），
 * 而不是 dashboard / settings 这类界面自己的图标 —— 菜单项是内容入口，不是控件。</p>
 */
export interface NavIcon {
  /** 传给 Icon 的 name */
  key: string
  label: string
}

export const NAV_ICONS: readonly NavIcon[] = [
  { key: 'bookshelf', label: '书架' },
  { key: 'fileText', label: '单篇' },
  { key: 'folder', label: '目录' },
  { key: 'list', label: '清单' },
  { key: 'grid', label: '网格' },
  { key: 'link', label: '链接' },
  { key: 'globe', label: '网站' },
  { key: 'clock', label: '时间' },
  { key: 'calendar', label: '日历' },
  { key: 'users', label: '团队' },
  { key: 'building', label: '机构' },
  { key: 'image', label: '图集' },
  { key: 'home', label: '首页' },
  { key: 'monitor', label: '屏幕' },
  { key: 'search', label: '搜索' },
  { key: 'download', label: '下载' },
  { key: 'eye', label: '可见' },
  { key: 'check', label: '完成' }
]

/** 菜单项未指定图标时按类型回退：目录用书架、文档用单篇。 */
export function navIconOf(icon: string | undefined, type: string | undefined): string {
  return icon || (type === 'dir' ? 'bookshelf' : 'fileText')
}

/** 清单里是否存在 —— 后端存下来的值可能是历史遗留或被手改过的。 */
export function isNavIcon(key: string | undefined): boolean {
  return !!key && NAV_ICONS.some((item) => item.key === key)
}
