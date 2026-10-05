import { defineStore } from 'pinia'
import { authApi } from '@/user/api'
import { TOKEN_KEY } from '@/shared/api/http'
import type { UserVO } from '@/shared/api/types'

/**
 * 用户侧登录态。
 *
 * <p>后端通过 HttpOnly Cookie（{@code minidocs_token}）桥接 Authorization 头，
 * 因此这里不依赖 localStorage 里的 token 是否存在来判断登录态 —— 一律问一次
 * {@code /api/auth/me}，401 就是游客。</p>
 */
export const useSessionStore = defineStore('session', {
  state: () => ({
    user: null as UserVO | null,
    resolved: false
  }),
  getters: {
    loggedIn: (state) => !!state.user,
    displayName: (state) => state.user?.displayName || state.user?.username || '',
    /** 头像地址；无头像时为空串，模板据此回退到首字母 */
    avatarSrc: (state) => state.user?.avatarSrc || '',
    initial(): string {
      const name = this.displayName.trim()
      return name ? name.charAt(0).toUpperCase() : 'U'
    }
  },
  actions: {
    set(user: UserVO | null) {
      this.user = user ?? null
      this.resolved = true
    },
    /** 解析登录态；未登录属正常结果，不抛错 */
    async resolve() {
      if (this.resolved) return this.user
      try {
        this.set(await authApi.me())
      } catch {
        this.set(null)
      }
      return this.user
    },
    async logout() {
      try {
        await authApi.logout()
      } catch {
        /* 已失效也继续清理本地状态 */
      }
      localStorage.removeItem(TOKEN_KEY)
      this.set(null)
    }
  }
})
