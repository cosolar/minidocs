import { defineStore } from 'pinia'
import { authApi } from '@/admin/api'
import { TOKEN_KEY } from '@/admin/api/http'
import type { UserVO } from '@/shared/api/types'
import { useSettingsStore } from '@/shared/stores/settings'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: null as UserVO | null,
    /**
     * 进入平台后要不要提醒重设密码。
     *
     * <p>只由「邮箱验证码登录」置 true；改完密码、登出或重新用密码登录都会清掉。
     * 不落 localStorage：这是一次登录的即时提醒，刷新后消失也不算遗漏 —— 账号设置里始终能改密码。</p>
     */
    passwordResetRequired: false
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
    /**
     * 登录。
     *
     * @param captchaTicket 人机验证通过后换到的一次性票据。它由登录页负责获取 ——
     *                      这个 store 只管会话，不掺和验证码的交互细节。
     */
    async login(username: string, password: string, captchaTicket: string) {
      const result = await authApi.login({ username, password, captchaTicket })
      this.token = result.token
      this.user = result.user
      this.passwordResetRequired = false
      localStorage.setItem(TOKEN_KEY, result.token)
      useSettingsStore().applyFromServer(result.settings as Record<string, unknown>)
      return result
    },
    /**
     * 邮箱验证码登录。
     *
     * <p>验证通过即进入平台；后端在返回里标了 {@code passwordResetRequired}，
     * 由管理端布局据它弹一次「重设密码」提醒。</p>
     */
    async loginByCode(account: string, code: string) {
      const result = await authApi.loginByCode({ account, code })
      this.token = result.token
      this.user = result.user
      this.passwordResetRequired = result.passwordResetRequired === true
      localStorage.setItem(TOKEN_KEY, result.token)
      useSettingsStore().applyFromServer(result.settings as Record<string, unknown>)
      return result
    },
    /** 重设密码成功后清掉提醒标记：提醒的目的已经达成。 */
    async resetPassword(newPassword: string) {
      await authApi.resetPassword(newPassword)
      this.passwordResetRequired = false
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
      this.passwordResetRequired = false
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem('minidocs.settings')
    }
  }
})
