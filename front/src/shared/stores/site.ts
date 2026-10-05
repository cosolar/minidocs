import { defineStore } from 'pinia'
import type { SiteConfig } from '@/shared/api/types'

/**
 * 站点品牌（名称 / 副标题 / Logo）的单一来源。
 *
 * <p>门户顶栏、阅读页、分享页、后台侧栏都读它，避免同一个名字在四处各写一份硬编码。
 * 读取通道与设置 store 同一套路数：共享层不反向依赖某一侧的 api 模块（否则阅读侧会把
 * 管理端的 api 一并打进包），由各应用在启动时注册实现。</p>
 */

export const DEFAULT_SITE: SiteConfig = { name: 'MiniDocs', subtitle: '极简知识库' }

export interface SiteGateway {
  get(): Promise<SiteConfig>
}

let gateway: SiteGateway | null = null

export function registerSiteGateway(impl: SiteGateway) {
  gateway = impl
}

/**
 * 模块级缓存的品牌名与当前页名。
 *
 * <p>路由的 {@code afterEach} 要拼「页面名 · 站点名」，但那里拿不到组件实例、也不该为了
 * 一个字符串去起 pinia。品牌名在进程内是常量，套用时同步写到这里即可；页名由路由每次
 * 导航写入，品牌名变化时再拼一次，两边都不必知道对方。</p>
 */
let siteName = DEFAULT_SITE.name
let pageTitle: string | undefined

/** 拼页面标题：无页面名时就是站点名本身。 */
export function siteTitle(title?: string): string {
  return title ? `${title} · ${siteName}` : siteName
}

/** 路由每完成一次导航就记下页名并刷新标题。 */
export function rememberPageTitle(title?: string) {
  pageTitle = title
  document.title = siteTitle(title)
}

export const useSiteStore = defineStore('site', {
  state: () => ({ value: { ...DEFAULT_SITE } as SiteConfig }),
  actions: {
    apply(payload: SiteConfig | null | undefined) {
      if (!payload) return
      this.value = { ...DEFAULT_SITE, ...payload }
      this.applyToDom()
    },
    /** 从服务端拉取并套用；未注册通道或请求失败时静默保留默认品牌 */
    async load() {
      if (!gateway) return this.value
      try {
        this.apply(await gateway.get())
      } catch {
        /* 离线或接口异常：沿用内置默认品牌，页面不该因为拿不到 Logo 就白屏 */
      }
      return this.value
    },
    applyToDom() {
      siteName = this.value.name || DEFAULT_SITE.name
      document.title = siteTitle(pageTitle)
      // 上传了 Logo 就顺手当站点图标；没配则不动 index.html 的默认值
      if (!this.value.logoSrc) return
      let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
      if (!link) {
        link = document.createElement('link')
        link.rel = 'icon'
        document.head.appendChild(link)
      }
      link.href = this.value.logoSrc
    }
  }
})
