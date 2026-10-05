import type { App } from 'vue'
import type { RouteLocationNormalized, RouteLocationRaw } from 'vue-router'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/admin/styles/admin.css'

import { registerSettingsGateway } from '@/shared/stores/settings'
import { settingsApi } from '@/admin/api'
import { useAuthStore } from '@/admin/stores/auth'
import { useOrgStore } from '@/admin/stores/org'

/**
 * 管理端外壳：Element Plus 装载 + 进入管理路由时的登录与组织判定。
 *
 * <p>只有路由守卫在 {@code meta.admin} 上动态 import 本模块，因此 EP 的 JS/CSS、管理端样式
 * 与管理端 api 层全部留在管理侧 chunk —— 规范 §3.4 要求阅读端首屏不加载 Element Plus，
 * 这条边界是硬约束，不能把管理端的任何东西静态引到入口去。</p>
 */
let installed = false

export async function enterAdmin(
  app: App | null,
  to: RouteLocationNormalized
): Promise<true | RouteLocationRaw> {
  if (!installed) {
    installed = true
    app?.use(ElementPlus, { locale: zhCn })
    // 设置读写换成管理侧通道：保存失败要弹提示，掉登录要跳登录页
    registerSettingsGateway(settingsApi)
  }

  const auth = useAuthStore()
  if (to.meta.public) {
    return true
  }
  if (!auth.token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!auth.user) {
    try {
      await auth.loadProfile()
    } catch {
      return { name: 'login' }
    }
  }
  /* 组织上下文在这里写一次，之后组件与 api 层只读它，绝不各读各的 localStorage */
  const org = to.params.org as string | undefined
  const store = useOrgStore()
  if (org) {
    store.enter(org)
    /*
     * 「登录后先去哪」也跟着地址栏走：切换器点选、直接输地址、收藏夹、同事发来的链接都从这条
     * 路径过，记在这里就只有一处。写失败留在本地值，便利项不值得弹错。
     */
    void store.rememberLast(org)
  } else if (!to.meta.public) {
    /*
     * 不带组织段的管理页（/account、/discover、/platform/users）必须把上下文清空：
     * 留着上一个组织的值，页面上任何一次误用 orgPath() 都会打到「人还看着账号设置、
     * 请求却写进了刚才那个组织」——那种错查起来比 requireOrg() 直接抛错难得多。
     */
    store.enter('')
  }
  if (to.meta.platformAdmin && !auth.isPlatformAdmin) {
    // 平台治理页：组织内角色再高也不是平台管理员，这里回工作区而不是给一个空壳页面
    return org ? { name: 'kbs', params: { org } } : { name: 'entry' }
  }
  return true
}
