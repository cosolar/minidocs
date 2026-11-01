import { defineStore } from 'pinia'
import type { SiteConfig } from '@/shared/api/types'
import { DEFAULT_FAVICON, DEFAULT_LOGO } from '@/shared/brand'

/**
 * 站点品牌（名称 / 副标题 / Logo）的单一来源。
 *
 * <p>门户顶栏、阅读页、分享页、后台侧栏都读它，避免同一个名字在四处各写一份硬编码。
 * 读取通道与设置 store 同一套路数：共享层不反向依赖某一侧的 api 模块（否则阅读侧会把
 * 管理端的 api 一并打进包），由各应用在启动时注册实现。</p>
 */

export const DEFAULT_SITE: SiteConfig = { name: 'MiniDocs', subtitle: '极简智库' }

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
  getters: {
    /**
     * 品牌位要显示的图片：优先横版 Logo，其次管理员上传的站点图标，最后内置圆形标记。
     *
     * <p>单个品牌位不再自己拼 {@code logoSrc || DEFAULT_LOGO}：新增图标兜底后，
     * 六个宿主各写一遍很容易漏掉一处，看起来就像「图标只在某些页面生效」。</p>
     */
    brandSrc(state): string {
      return state.value.logoSrc || state.value.iconSrc || DEFAULT_LOGO
    },
    /**
     * 品牌位 Logo 框的行内尺寸，只覆盖管理员显式配过的那一边。
     *
     * <p>没配就是空对象 —— 各品牌位（顶栏 34、侧栏 32、登录 44、页脚 26）继续用自己的默认值。
     * 页脚刻意不绑这个尺寸：那里是 26px 的小位，塞进一个 160×60 的横版标志会把整条页脚顶高，
     * 尺寸设置的作用范围因此是「除页脚外的品牌位」。</p>
     *
     * <p>没有 Logo、回落到站点图标时同样返回空对象：宽高是给横版图配的，
     * 套到正方形图标上会把它拉变形，也不符合各品牌位原本的小尺寸。</p>
     */
    logoStyle(state): Record<string, string> {
      const style: Record<string, string> = {}
      if (!state.value.logoSrc) {
        return style
      }
      if (state.value.logoWidth) {
        style.width = `${state.value.logoWidth}px`
      }
      if (state.value.logoHeight) {
        style.height = `${state.value.logoHeight}px`
      }
      return style
    },
    /**
     * 是否「只出图、不出名称与副标题」。
     *
     * <p>要求至少有图可出：横版 Logo 或站点图标任一即可。只开开关却没传图时，
     * 品牌位会只剩一个内置标记、站点叫什么全没了，看起来像配置没生效。
     * 开关的存储值照旧回显给设置页，这里只约束渲染。</p>
     */
    logoOnly(state): boolean {
      return Boolean(state.value.logoOnly) && Boolean(state.value.logoSrc || state.value.iconSrc)
    }
  },
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
      // 标签页图标只认「站点图标」：横版 Logo 是给品牌位用的，压进 16px 只剩一条色带。
      // 移除图标后要显式回到内置那张，否则会留着已经删掉的旧地址。
      const icon = this.value.iconSrc || DEFAULT_FAVICON
      let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
      if (!link) {
        link = document.createElement('link')
        link.rel = 'icon'
        document.head.appendChild(link)
      }
      link.href = icon
    }
  }
})
