import { defineStore } from 'pinia'
import { meApi } from '@/admin/api'
import { setOrg } from '@/admin/api/context'
import { useSettingsStore } from '@/shared/stores/settings'
import type { TenantBrief } from '@/shared/api/types'

/**
 * 组织上下文仓库。
 *
 * <p>唯一写入者是路由守卫：它把地址里的 {@code /console/{org}} 同步进这里与 api 层。
 * 因此刷新、新开标签页、把链接发给同事，三种情况都会落到同一个组织上 —— 组织不藏在
 * localStorage 里（规范 §3.4）。</p>
 *
 * <p>{@code tenants} 只服务于落地页与顶栏切换器，不参与任何权限判断；后端每个请求仍会自查成员门。</p>
 */
export const useOrgStore = defineStore('org', {
  state: () => ({
    /** 地址栏带来的当前组织 slug，空串表示还没有进入任何组织页面 */
    org: '',
    tenants: [] as TenantBrief[],
    lastTenantSlug: '',
    loaded: false,
    inflight: null as Promise<void> | null
  }),
  getters: {
    current(state): TenantBrief | null {
      return state.tenants.find((tenant) => tenant.slug === state.org) ?? null
    },
    /** 登录后该去哪：上次的组织若仍属于我就用它，否则第一个（个人组织由后端置顶）。 */
    entry(state): string {
      if (state.lastTenantSlug && state.tenants.some((tenant) => tenant.slug === state.lastTenantSlug)) {
        return state.lastTenantSlug
      }
      return state.tenants[0]?.slug || ''
    }
  },
  actions: {
    /** 由路由守卫调用：地址栏说什么，这里就是什么。 */
    enter(slug: string) {
      this.org = slug
      setOrg(slug)
    },
    /**
     * 记住「这次进的是哪个组织」，供下次登录落地。
     *
     * <p>切换器里点一下已经写过一次，但直接输地址、从收藏夹打开、同事发来的链接都不会经过
     * 那条路径 —— 只有守卫看得见所有进入方式，所以记落点也放在这里。写入是便利项：失败留在
     * 本地值，服务端存的仍是上一次的，不值得弹错。</p>
     */
    async rememberLast(slug: string) {
      if (!slug || this.lastTenantSlug === slug) {
        return
      }
      this.lastTenantSlug = slug
      await useSettingsStore().patch({ lastTenantSlug: slug })
    },
    async load(force = false) {
      if (this.loaded && !force) {
        return
      }
      if (!this.inflight) {
        this.inflight = meApi
          .me()
          .then((me) => {
            this.tenants = me.tenants || []
            this.lastTenantSlug = me.lastTenantSlug || ''
            this.loaded = true
          })
          .finally(() => {
            this.inflight = null
          })
      }
      await this.inflight
    },
    /** 换账号后旧的组织清单就是别人的了。 */
    reset() {
      this.org = ''
      this.tenants = []
      this.lastTenantSlug = ''
      this.loaded = false
      setOrg(null)
    }
  }
})
