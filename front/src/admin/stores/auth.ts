import { defineStore } from 'pinia'
import { authApi } from '@/admin/api'
import { TOKEN_KEY } from '@/admin/api/http'
import type { UserVO } from '@/shared/api/types'
import { useSettingsStore } from '@/shared/stores/settings'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: null as UserVO | null
  }),
  getters: {
    /**
     * 平台角色：只管账号本身的治理（审核、启停、重置密码），与任何组织的 OWNER/ADMIN 无关。
     *
     * <p>整条前端只有这一处读 {@code user.role} 的字面量，其余地方一律用这个 getter：后端把
     * 门槛写在 {@code /api/platform/**} 的拦截器上，判定口径同名不同源就会漂。</p>
     */
    isPlatformAdmin: (state) => state.user?.role === 'ADMIN',
    nickname: (state) => state.user?.displayName || state.user?.username || '',
    /** 头像地址；无头像时为空串，模板据此回退到首字母 */
    avatarSrc: (state) => state.user?.avatarSrc || ''
  },
  actions: {
    async login(username: string, password: string) {
      const result = await authApi.login({ username, password })
      this.token = result.token
      this.user = result.user
      localStorage.setItem(TOKEN_KEY, result.token)
      useSettingsStore().applyFromServer(result.settings as Record<string, unknown>)
      return result
    },
    async loadProfile() {
      this.user = await authApi.me()
      return this.user
    },
    /** 头像上传 / 移除后后端回吐新的 UserVO，用它替换会话里的那一份 */
    setUser(user: UserVO) {
      this.user = user
      return this.user
    },
    async updateAccount(payload: { username?: string; displayName?: string; email?: string }) {
      this.user = await authApi.updateAccount(payload)
      return this.user
    },
    async logout() {
      try {
        await authApi.logout()
      } catch {
        /* 忽略登出异常 */
      }
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem('minidocs.settings')
    }
  }
})
