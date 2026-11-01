import { createApp } from 'vue'
import { createPinia } from 'pinia'

// KaTeX 自身的排版样式（原实现漏了这一步，公式会以未排版形态呈现）
import 'katex/dist/katex.min.css'
import '@/user/styles/reader.css'

import App from './App.vue'
import router, { bindApp } from './router'
import { settingsApi, siteApi, themeApi } from '@/user/api'
import { registerSettingsGateway, useSettingsStore } from '@/shared/stores/settings'
import { registerSiteGateway, useSiteStore } from '@/shared/stores/site'
import { initReaderFont } from '@/shared/readerFont'
import { initReaderWidth } from '@/shared/readerWidth'
import { initReaderSize } from '@/shared/readerSize'
import {
  initReaderTheme,
  loadReaderThemes,
  registerThemeGateway,
  setServerDefaultTheme
} from '@/shared/readerTheme'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
// 守卫在首次导航里就要拿到实例来装 Element Plus，所以必须先于 app.use(router)
bindApp(app)
app.use(router)

/*
 * 共享设置 store 的读写通道由各应用注册，避免共享层反向依赖某一侧的 api 模块。
 * 默认注册阅读侧通道（它不牵连 Element Plus）；进入管理端时 shell 会换成管理侧那一份。
 */
registerSettingsGateway(settingsApi)
registerSiteGateway(siteApi)
registerThemeGateway(themeApi)

const settings = useSettingsStore(pinia)
settings.applyToDom()
// 游客会拿到 401，load() 内部已静默处理
void settings.load()

// 正文字体、正文宽度、整体字号与阅读主题都只落本地：写的都是根元素上的 CSS 变量，
// 之后切路由不必再管。主题先用缓存上色，再异步拉一次已安装列表校正。
initReaderFont()
initReaderWidth()
initReaderSize()
initReaderTheme()
void loadReaderThemes()

// 站点品牌是公开接口，游客也能读到；失败时保留内置默认品牌
const site = useSiteStore(pinia)
site.applyToDom()
void site.load().then(() => setServerDefaultTheme(site.value.readerTheme))

app.mount('#app')
