import { defineStore } from 'pinia'

export interface UiSettings {
  showDocTree: boolean
  contentWidth: number
  codeTheme: string
  themeMode: 'light' | 'dark' | 'system'
  defaultView: string
  kbView: 'grid' | 'list'
  [key: string]: unknown
}

/**
 * 设置的服务端读写通道。
 *
 * <p>共享层不直接依赖任何一侧的 api 模块（否则用户侧会把 Element Plus 一并打进包），
 * 由各应用在启动时注册自己的实现。</p>
 */
export interface SettingsGateway {
  get(): Promise<Record<string, unknown>>
  update(patch: Record<string, unknown>): Promise<Record<string, unknown>>
}

let gateway: SettingsGateway | null = null

export function registerSettingsGateway(impl: SettingsGateway) {
  gateway = impl
}

const STORAGE_KEY = 'minidocs.settings'

export const DEFAULT_SETTINGS: UiSettings = {
  showDocTree: true,
  contentWidth: 900,
  codeTheme: 'github',
  themeMode: 'system',
  defaultView: 'read',
  kbView: 'grid'
}

function read(): UiSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? { ...DEFAULT_SETTINGS, ...JSON.parse(raw) } : { ...DEFAULT_SETTINGS }
  } catch {
    return { ...DEFAULT_SETTINGS }
  }
}

export const useSettingsStore = defineStore('settings', {
  state: () => ({ value: read() as UiSettings }),
  actions: {
    applyFromServer(payload: Record<string, unknown> | null | undefined) {
      if (!payload) return
      this.value = { ...this.value, ...payload } as UiSettings
      this.persist()
      this.applyToDom()
    },
    /** 从服务端拉取并套用；未注册通道或请求失败时静默保留本地值 */
    async load() {
      if (!gateway) return this.value
      try {
        this.applyFromServer(await gateway.get())
      } catch {
        /* 游客或无权限，沿用本地设置 */
      }
      return this.value
    },
    async patch(patch: Partial<UiSettings>) {
      this.value = { ...this.value, ...patch } as UiSettings
      this.persist()
      this.applyToDom()
      if (!gateway) return
      try {
        this.applyFromServer(await gateway.update(patch as Record<string, unknown>))
      } catch {
        /* 保存失败保留本地值 */
      }
    },
    /**
     * 只改本地，不发网关。
     *
     * <p>给访客页面用（分享页的外观切换）：访客没有身份，{@code patch} 发过去的更新必然 401，
     * 每点一次就多一条无谓的失败请求与一次控制台报错。外观这类纯偏好本来就是「这台浏览器」
     * 的事，落在 localStorage 已经够了。</p>
     */
    patchLocal(patch: Partial<UiSettings>) {
      this.value = { ...this.value, ...patch } as UiSettings
      this.persist()
      this.applyToDom()
    },
    persist() {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.value))
    },
    applyToDom() {
      const root = document.documentElement
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
      const mode = this.value.themeMode === 'system' ? (prefersDark ? 'dark' : 'light') : this.value.themeMode
      root.setAttribute('data-theme', mode)
      root.setAttribute('data-code-theme', this.value.codeTheme || 'github')
      root.style.setProperty('--md-content-width', `${this.value.contentWidth || 900}px`)
    }
  }
})
