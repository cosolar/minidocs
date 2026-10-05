import { createRouter, createWebHistory } from 'vue-router'
import type { App } from 'vue'
import { rememberScroll, restoreScroll } from '@/shared/scrollRegion'
import { APP_BASE } from '@/shared/appBase'
import { rememberPageTitle } from '@/shared/stores/site'

/**
 * 单 SPA 路由表（规范 §3.4）。
 *
 * <p>阅读端占 {@code /}、{@code /kb/**}、{@code /share/**}；管理端占 {@code /console/{org}/**}，
 * 以及三类不带组织的路径：登录注册（{@code /login}、{@code /register}）、账号与组织发现
 * （{@code /account}、{@code /discover}）、平台治理（{@code /platform/users}）。
 * 地址里的组织段是组织上下文的唯一来源，因此组织内页面一律带 {@code /console/{org}}
 * （{@code /console} 只是落地中转，解析完立刻改写成 {@code /console/{org}/kbs}）。</p>
 *
 * <p>整站挂在 {@code PAGE_BASE}（默认站点根 {@code /}）之下：路由表里写的仍是不带前缀的相对路径，
 * base 由 {@code createWebHistory} 统一套上，因此 {@code router.push('/console')} 与
 * {@code <router-link to="/kb/...">} 都不用自己带前缀。注意它与后端的 context-path（{@code BACKEND_BASE}，
 * 默认 {@code /minidocs}）是两个独立前缀 —— 后端接口与后端拼出来的地址带后者，前者只管页面。
 * 详见 {@code shared/appBase.ts}。</p>
 *
 * <p>路径与后端 SPA 回退映射保持一致：{@code SpaFallbackController} 把上面这些前缀一律
 * forward 到 {@code /index.html}，深链接刷新不会 404。</p>
 */

/** 组织内页面的路径前缀。 */
const ORG = '/console/:org'

let appRef: App | null = null

/** 守卫要在管理端首次进入时 {@code app.use(ElementPlus)}，因此需要拿到应用实例。 */
export function bindApp(app: App) {
  appRef = app
}

const router = createRouter({
  history: createWebHistory(`${APP_BASE}/`),
  routes: [
    /* ------------------------------------------------------------- 阅读端 */
    {
      path: '/',
      name: 'portal',
      component: () => import('@/user/views/PortalHomeView.vue'),
      meta: { reader: true, title: '知识库' }
    },
    {
      path: '/kb/:orgSlug/:slug',
      name: 'read',
      component: () => import('@/user/views/ReaderView.vue'),
      meta: { reader: true, title: '阅读' }
    },
    {
      // v1 老链接：唯一命中由后端 301，走到这里是要消歧或未找到（规范 §7.1.3）
      path: '/kb/:slug',
      name: 'legacy-read',
      component: () => import('@/user/views/LegacyKbView.vue'),
      meta: { reader: true, title: '阅读' }
    },
    {
      // 分享页无顶栏，避免访客产生「已登录」的错觉
      path: '/share/:token',
      name: 'share',
      component: () => import('@/user/views/ShareView.vue'),
      meta: { title: '分享阅读' }
    },
    /* ------------------------------------------------------------- 管理端 */
    {
      path: '/login',
      name: 'login',
      component: () => import('@/admin/views/LoginView.vue'),
      meta: { admin: true, public: true, title: '登录' }
    },
    /**
     * 注册页与登录页一样是 public：{@code enterAdmin} 在这两个路径上只装 Element Plus
     * 外壳，不检查 token。后端注册成功后开关若被关掉，返回的错误码由拦截器提示。
     */
    {
      path: '/register',
      name: 'register',
      component: () => import('@/admin/views/RegisterView.vue'),
      meta: { admin: true, public: true, title: '注册' }
    },
    /** 落地页：组织由 /api/me 决定，解析完立刻改写成 {@code /console/{org}/kbs}。 */
    {
      path: '/console',
      name: 'entry',
      component: () => import('@/admin/views/EntryView.vue'),
      meta: { admin: true, title: '工作区' }
    },
    /**
     * 文章编辑页是全屏工作区（顶部自带「返回知识库」入口），
     * 不套后台布局，避免侧边导航挤压编辑与预览的可用宽度。
     */
    {
      path: `${ORG}/kbs/:slug`,
      name: 'kb-workspace',
      component: () => import('@/admin/views/KbWorkspaceView.vue'),
      meta: { admin: true, title: '知识库工作区' }
    },
    /**
     * 不带组织的管理页共用一套外壳：账号设置是「我自己的」，发现组织是「我还没进去的」，
     * 平台用户管理是「平台角色的」。三者都没有 {@code /console/{org}} 前缀，因此 api 层在
     * 这些路径上不允许调用 {@code requireOrg()}（规范 §3.4 的两条通道分界）。
     *
     * <p>没有空的子路由，所以 {@code /} 仍然由上面的门户首页命中。</p>
     */
    {
      path: '/',
      component: () => import('@/admin/layouts/AdminLayout.vue'),
      meta: { admin: true },
      children: [
        { path: 'account', name: 'account', component: () => import('@/admin/views/AccountView.vue'), meta: { admin: true, title: '账号设置' } },
        { path: 'discover', name: 'discover', component: () => import('@/admin/views/DiscoverView.vue'), meta: { admin: true, title: '发现组织' } },
        {
          path: 'platform/users',
          name: 'platform-users',
          component: () => import('@/admin/views/UserManageView.vue'),
          meta: { admin: true, platformAdmin: true, title: '平台用户管理' }
        },
        /**
         * 站点设置改的是「整个部署」的品牌（名称 / Logo），不属于任何组织，因此与平台用户管理
         * 同处一条不带组织段的通道，门槛也同是平台角色（{@code meta.platformAdmin}）。
         */
        {
          path: 'platform/site',
          name: 'platform-site',
          component: () => import('@/admin/views/SiteSettingsView.vue'),
          meta: { admin: true, platformAdmin: true, title: '站点设置' }
        }
      ]
    },
    {
      path: ORG,
      component: () => import('@/admin/layouts/AdminLayout.vue'),
      meta: { admin: true },
      children: [
        { path: '', redirect: { name: 'kbs' } },
        { path: 'overview', name: 'overview', component: () => import('@/admin/views/OverviewView.vue'), meta: { admin: true, title: '系统概览' } },
        { path: 'kbs', name: 'kbs', component: () => import('@/admin/views/KbManageView.vue'), meta: { admin: true, title: '知识库管理' } },
        { path: 'shares', name: 'shares', component: () => import('@/admin/views/ShareManageView.vue'), meta: { admin: true, title: '分享管理' } },
        /**
         * 组织设置与审计的门槛在**组织内**（后端按动作向量裁决），与平台角色无关，所以路由表
         * 这里不加任何 gate：组件读组织详情出参上的 {@code myPermissions} 收敛可操作项，
         * 非成员进不来是成员门的事（{@code TenantInterceptor} 直接 404，规范 §2.5）。
         */
        { path: 'settings', name: 'org-settings', component: () => import('@/admin/views/OrgSettingsView.vue'), meta: { admin: true, title: '组织设置' } },
        { path: 'audit', name: 'audit', component: () => import('@/admin/views/AuditView.vue'), meta: { admin: true, title: '审计日志' } }
      ]
    },
    /* --------------------------------------------------------------- 兜底 */
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/user/views/NotFoundView.vue'),
      meta: { title: '页面不存在' }
    }
  ],
  scrollBehavior(to) {
    /*
     * 页面不滚（滚动区是 #md-view），所以 router 那一套基于 window 的位置恢复一律是 0，
     * 交给它只会得到「永远停在顶部」；恢复改由 scrollRegion 自己做（规范 §11.13）。
     * 这里只做「滚到哪」，「从哪来」的位置是在 beforeEach 记的，两件事不能合并：
     * scrollBehavior 是在新页面已经渲染完之后再调的，那一刻旧位置已经被浏览器夹回 0。
     * hash 例外：`{ el }` 内部走 scrollIntoView，它会自己找最近的滚动祖先，不受影响。
     */
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    restoreScroll(to.fullPath)
    return false
  }
})

router.beforeEach((to, from) => {
  // 守卫是最早还能读到旧页面滚动位置的地方，晚一步（scrollBehavior）内容就已经换掉了
  if (to.fullPath !== from.fullPath && from.name) rememberScroll(from.fullPath)
  return true
})

router.beforeEach(async (to) => {
  if (!to.meta.admin) {
    return true
  }
  /*
   * Element Plus、管理端样式与登录守卫一起放在按需加载的壳里：阅读端首屏永远不会
   * 走到这个分支，EP 及其 CSS 就不会进阅读端的 chunk（规范 §3.4 的硬指标）。
   */
  const { enterAdmin } = await import('@/admin/shell')
  return enterAdmin(appRef, to)
})

router.afterEach((to) => {
  // 站点名可能被后台改过，标题由站点 store 统一拼（见 shared/stores/site.ts）
  rememberPageTitle(to.meta.title as string | undefined)
})

export default router
