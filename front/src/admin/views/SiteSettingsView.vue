<script setup lang="ts">
/**
 * 站点设置：品牌（名称 / 副标题 / Logo / 图标）/ 分享链接 / 邮件与邮箱验证 / 阅读主题（路由 {@code /platform/site}）。
 *
 * <p>这一页改的是「整个部署」的品牌，不是某个组织、也不是某个人：所以它不带组织段，
 * 门槛是平台角色（路由 meta.platformAdmin + 后端 {@code /api/platform/site} 的 AdminInterceptor）。
 * 保存成功后把出参回灌共享 site store，顶栏、侧栏、页面标题立即跟着变，不必刷新。</p>
 *
 * <p>图片分两种用途：横版 Logo 用于站内品牌位，正方形图标用于浏览器标签页、并在没有 Logo 时兜底
 * 品牌位。标签页只有 16~32px，横版组合图压进去只剩一条色带，所以两者不共用一张图。</p>
 *
 * <p>站点基址（{@code baseUrl}）管的是分享链接：改了之后所有新复制出去的链接立即跟着变。
 * 留空则回落到部署配置 {@code PAGE_BASE_URL}，再没有就按当前请求推导 ——
 * 最后一层要求同源部署，反代后面直接用会让链接指向内网地址。</p>
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { siteApi, themeApi } from '@/admin/api'
import MdIcon from '@/admin/components/MdIcon.vue'
import HelpTip from '@/shared/components/HelpTip.vue'
import { useSiteStore } from '@/shared/stores/site'
import type { SiteConfig, SiteMailVO } from '@/shared/api/types'
import { DEFAULT_FAVICON, DEFAULT_LOGO } from '@/shared/brand'
import { DEFAULT_THEME, themeSwatch, type ThemePack } from '@/shared/readerTheme'
import { downloadThemeTemplate } from '@/shared/themeTemplate'

const site = useSiteStore()

const loading = ref(true)
const saving = ref(false)
const uploading = ref(false)
const uploadingIcon = ref(false)
const fileRef = ref<HTMLInputElement | null>(null)
const iconFileRef = ref<HTMLInputElement | null>(null)

const form = reactive({ name: '', subtitle: '', baseUrl: '', readerTheme: '' })

/**
 * Logo 显示方式：宽高留空 = 跟随各品牌位默认尺寸，「只显示 Logo」= 隐藏名称与副标题。
 *
 * <p>三者都是纯渲染参数，存在 site_config 的同一份 JSON 里，与图片文件本身无关 ——
 * 换图不必重设尺寸，改尺寸也不会动图。</p>
 */
const logoForm = reactive({ width: null as number | null, height: null as number | null, only: false })
const baseUrlSaving = ref(false)

/** 预览跟着表单走：管理员要在这里看到最终效果，而不是等保存后再去顶栏核对 */
const logoPreviewStyle = computed(() => {
  const style: Record<string, string> = {}
  if (logoForm.width) {
    style.width = `${logoForm.width}px`
  }
  if (logoForm.height) {
    style.height = `${logoForm.height}px`
  }
  return style
})

/** 预览优先用刚保存下来的地址；还没保存过的本地选择不做即时预览，避免与后端真值不一致 */
const logoSrc = computed(() => site.value.logoSrc || '')
const iconSrc = computed(() => site.value.iconSrc || '')

/**
 * 本地即时校验，与后端 {@code SiteBaseUrls.require} 同一套规则。
 *
 * <p>放在前端是为了少一次往返，不是为了取代后端校验：绕过界面的调用（比如脚本）照样要过那一关。</p>
 */
const baseUrlError = computed(() => {
  const value = form.baseUrl.trim()
  if (!value) return ''
  if (value.length > 200) return '不能超过 200 个字符'
  const withScheme = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(value) ? value : `https://${value}`
  if (!/^https?:\/\//i.test(withScheme)) return '只支持 http 或 https'
  if (!/^https?:\/\/[^/?#\s]+/i.test(withScheme)) return '缺少主机名'
  return ''
})

async function load() {
  loading.value = true
  try {
    const data = await siteApi.get()
    site.apply(data)
    form.name = data.name || ''
    form.subtitle = data.subtitle || ''
    form.baseUrl = data.baseUrl || ''
    form.readerTheme = data.readerTheme || ''
    logoForm.width = data.logoWidth ?? null
    logoForm.height = data.logoHeight ?? null
    logoForm.only = Boolean(data.logoOnly)
  } finally {
    loading.value = false
  }
}

/** 后端回填的名称与显示参数：把归一化结果写回表单，别让人对着旧值再改一遍。 */
function syncBrandForm(data: SiteConfig) {
  form.name = data.name
  form.subtitle = data.subtitle || ''
  logoForm.width = data.logoWidth ?? null
  logoForm.height = data.logoHeight ?? null
  logoForm.only = Boolean(data.logoOnly)
}

/**
 * 保存品牌：名称、副标题与 Logo 显示参数一起提交。
 *
 * <p>清空尺寸要发空串而不是省略字段：后端按「patch 里显式带了这一项」判定，
 * 不带 = 保持原值，带空串 = 清除回默认尺寸。少发一个字段就会留下一个改不掉的旧尺寸。</p>
 */
async function saveBrand() {
  if (!form.name.trim()) {
    ElMessage.warning('站点名称不能为空')
    return
  }
  saving.value = true
  try {
    const data = await siteApi.update({
      name: form.name.trim(),
      subtitle: form.subtitle.trim(),
      logoWidth: logoForm.width ?? '',
      logoHeight: logoForm.height ?? '',
      logoOnly: logoForm.only
    })
    site.apply(data)
    syncBrandForm(data)
    ElMessage.success('站点品牌已更新')
  } finally {
    saving.value = false
  }
}

async function saveBaseUrl() {
  if (baseUrlError.value) {
    ElMessage.warning(baseUrlError.value)
    return
  }
  baseUrlSaving.value = true
  try {
    const data = await siteApi.update({ baseUrl: form.baseUrl.trim() })
    site.apply(data)
    // 回填后端归一化过的值（补上的 https://、去掉的尾斜杠），别让输入框里留着一段需要人脑补的字符串
    form.baseUrl = data.baseUrl || ''
    ElMessage.success('站点基址已更新')
  } finally {
    baseUrlSaving.value = false
  }
}

function pickLogo() {
  fileRef.value?.click()
}

async function onFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  uploading.value = true
  try {
    site.apply(await siteApi.uploadLogo(file))
    ElMessage.success('Logo 已更新')
  } finally {
    uploading.value = false
  }
}

async function removeLogo() {
  site.apply(await siteApi.removeLogo())
  ElMessage.success('Logo 已移除')
}

function pickIcon() {
  iconFileRef.value?.click()
}

async function onIconFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  uploadingIcon.value = true
  try {
    site.apply(await siteApi.uploadIcon(file))
    ElMessage.success('站点图标已更新')
  } finally {
    uploadingIcon.value = false
  }
}

async function removeIcon() {
  site.apply(await siteApi.removeIcon())
  ElMessage.success('站点图标已移除')
}

/* ------------------------------------------------------------------ 邮件与邮箱验证 */
/*
 * 这一块的读接口和品牌配置分开（{@code /platform/site/mail}）：品牌是公开的，分享页也要读；
 * 发件参数属于这台机器的基础设施，只在平台管理员通道出现。
 *
 * 口令不回显，所以表单里那个输入框的语义是「要不要换」而不是「现在是什么」：
 * 留空保存即沿用已存的那一个，要删掉得显式点「清除已存口令」。
 */
const mail = ref<SiteMailVO>({})
const mailLoading = ref(false)
const mailSaving = ref(false)
const mailTesting = ref(false)
const mailTestTo = ref('')
const clearMailPassword = ref(false)

const mailForm = reactive({
  smtpHost: '',
  smtpPort: null as number | null,
  smtpSecurity: '',
  smtpUsername: '',
  smtpFrom: '',
  smtpPassword: '',
  emailVerifyEnabled: false
})

/**
 * 没显式选加密方式时会被怎么连接 —— 与后端 {@code MailSettings#effectiveSecurity} 同一套推断。
 *
 * <p>写在本地是为了让「自动」这一项当场说清自己是什么，不用等保存后再去猜；
 * 后端仍自己判一遍，这里只是给人看的说明。</p>
 */
const securityAutoHint = computed(() => {
  if (mailForm.smtpSecurity) {
    return ''
  }
  if (!mailForm.smtpPort) {
    return '未填端口时按 STARTTLS 连接'
  }
  return mailForm.smtpPort === 465 ? '端口 465 按 SSL 直连' : `端口 ${mailForm.smtpPort} 按 STARTTLS 升级`
})

/** 已存配置的结论：只看后端回吐的三个布尔，不由前端自己与一遍。 */
const verificationSummary = computed(() => {
  if (!mail.value.emailVerifyEnabled) {
    return '未开启：注册后立刻可以登录，不要求验证邮箱。'
  }
  if (mail.value.emailVerifyRequired) {
    return '已生效：注册页要求先填入邮箱收到的 6 位验证码，填对才建号。'
  }
  return '开关已打开，但发件参数不齐，验证邮件发不出去 —— 目前仍按不验证放行。填齐主机、端口、发件地址并保存后自动生效。'
})

/** 徽标只说结论，长解释留给下面那句 —— 一行里塞两段话就谁也读不完。 */
const verificationChip = computed(() => {
  if (mail.value.emailVerifyRequired) {
    return { text: '已生效', tone: 'on' }
  }
  return mail.value.emailVerifyEnabled
    ? { text: '未生效', tone: 'warn' }
    : { text: '未开启', tone: 'off' }
})

/** 把后端回吐的脱敏视图灌回表单；口令永远不回显，所以每次都要重置成空。 */
function applyMail(data: SiteMailVO) {
  mail.value = data
  mailForm.smtpHost = data.smtpHost || ''
  mailForm.smtpPort = data.smtpPort ?? null
  mailForm.smtpSecurity = data.smtpSecurity || ''
  mailForm.smtpUsername = data.smtpUsername || ''
  mailForm.smtpFrom = data.smtpFrom || ''
  mailForm.smtpPassword = ''
  mailForm.emailVerifyEnabled = Boolean(data.emailVerifyEnabled)
  clearMailPassword.value = false
}

async function loadMail() {
  mailLoading.value = true
  try {
    applyMail(await siteApi.getMail())
  } finally {
    mailLoading.value = false
  }
}

/**
 * 保存邮件配置。
 *
 * <p>清空某一项要发空串而不是省略：后端按「patch 里显式带了这一项」判定，不带 = 保持原值。
 * 口令是唯一反过来的字段 —— 回显里没有它，空串只能是「沿用原口令」，清除必须显式发
 * {@code smtpPasswordClear}。</p>
 */
async function saveMail() {
  const keepsStoredPassword = mail.value.smtpPasswordSet && !clearMailPassword.value
  // 用户名与口令要么都给、要么都不给：只给用户名时服务器收到的是未认证投递，
  // 会回一句读起来像「主机填错了」的 503
  if (mailForm.smtpUsername.trim() && !mailForm.smtpPassword && !keepsStoredPassword) {
    ElMessage.warning('填了 SMTP 用户名就要填口令；不需要认证的中继请把用户名也清空')
    return
  }
  mailSaving.value = true
  try {
    const patch: Record<string, unknown> = {
      smtpHost: mailForm.smtpHost.trim(),
      smtpPort: mailForm.smtpPort ?? '',
      smtpSecurity: mailForm.smtpSecurity,
      smtpUsername: mailForm.smtpUsername.trim(),
      smtpFrom: mailForm.smtpFrom.trim(),
      emailVerifyEnabled: mailForm.emailVerifyEnabled
    }
    if (mailForm.smtpPassword) {
      patch.smtpPassword = mailForm.smtpPassword
    } else if (clearMailPassword.value) {
      // 两者互斥：填了新口令还带 clear，后端的清除会盖掉刚写的那个
      patch.smtpPasswordClear = true
    }
    applyMail(await siteApi.updateMail(patch))
    ElMessage.success('邮件配置已保存')
    // 重新拉一次公开配置：`emailVerifyRequired` 变了，注册页的文案要跟着改，不该等人刷新浏览器
    void site.load()
  } finally {
    mailSaving.value = false
  }
}

/**
 * 测试发信：用**已存**的参数真发一封。
 *
 * <p>所以要先保存再测 —— 后端读的是库里的配置，拿没保存的表单去发会让人以为参数是对的。
 * 失败原因（证书、端口、认证、服务商拦截第三方客户端）由后端原文弹出，那是这一句的全部价值。</p>
 */
async function sendTestMail() {
  const to = mailTestTo.value.trim()
  if (!to) {
    ElMessage.warning('先填写收件地址')
    return
  }
  if (!mail.value.mailReady) {
    ElMessage.warning('SMTP 参数还没填齐并保存，先发不了')
    return
  }
  mailTesting.value = true
  try {
    await siteApi.testMail(to)
    ElMessage.success('测试邮件已发出，请查收（也看看垃圾邮件箱）')
  } finally {
    mailTesting.value = false
  }
}

/* ------------------------------------------------------------------ 阅读主题 */
/*
 * 主题包是「服务器侧的文件」：装着几张 JSON，读者的浏览器拉列表后自己拼样式。
 * 所以这里不需要构建或发布前端 —— 装上刷新生效，卸载同理。default.json 与其他内置包
 * 同一套种子机制落盘，列表里带着它的全套令牌，卸载与上传同名包都被后端拒绝。
 */
const themes = ref<ThemePack[]>([])
const themesLoading = ref(false)
const themeUploading = ref(false)
const themeReseeding = ref(false)
const themeFileRef = ref<HTMLInputElement | null>(null)

const themeCards = computed(() =>
  themes.value.map((pack) => ({
    pack,
    light: themeSwatch(pack, 'light'),
    dark: themeSwatch(pack, 'dark')
  }))
)

const readerThemeSaving = ref(false)

/** 未设置时新访客跟随内置澄澈，所以「默认」在界面上就是澄澈这张卡片 */
const currentDefaultId = computed(() => form.readerTheme || DEFAULT_THEME.id)

function isDefaultTheme(pack: ThemePack) {
  return pack.id === currentDefaultId.value
}

/**
 * 点卡片即设为新访客默认主题。
 *
 * <p>空串 = 清除（回到内置澄澈）：readerTheme 的语义是「覆盖」，没有值才是跟随内置，
 * 所以点澄澈卡片发的是空串而不是 "default"。</p>
 */
async function setDefaultTheme(pack: ThemePack) {
  if (readerThemeSaving.value || isDefaultTheme(pack)) return
  readerThemeSaving.value = true
  try {
    const nextId = pack.id === DEFAULT_THEME.id ? '' : pack.id
    const data = await siteApi.update({ readerTheme: nextId })
    site.apply(data)
    form.readerTheme = data.readerTheme || ''
    ElMessage.success(nextId ? `新访客将使用「${pack.name}」` : '新访客将跟随内置「澄澈」')
  } finally {
    readerThemeSaving.value = false
  }
}

async function loadThemes() {
  themesLoading.value = true
  try {
    themes.value = await themeApi.list()
  } finally {
    themesLoading.value = false
  }
}

function pickThemeFile() {
  themeFileRef.value?.click()
}

async function onThemeFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.json')) {
    ElMessage.warning('主题包必须是 .json 文件')
    return
  }
  themeUploading.value = true
  try {
    const pack = await themeApi.install(file)
    ElMessage.success(`主题「${pack.name}」已安装`)
    await loadThemes()
  } finally {
    themeUploading.value = false
  }
}

async function uninstallTheme(pack: ThemePack) {
  const fallback = pack.id === DEFAULT_THEME.id ? '内置基础样式' : '默认主题'
  try {
    await ElMessageBox.confirm(
      `卸载后读者将看不到「${pack.name}」；已经选了它的浏览器会自动回落到${fallback}。`,
      '卸载主题',
      { type: 'warning', confirmButtonText: '卸载', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await themeApi.uninstall(pack.id)
  ElMessage.success('主题已卸载')
  await loadThemes()
}

async function reseedThemes() {
  try {
    await ElMessageBox.confirm(
      '内置主题会被重新写入并覆盖同名文件；你在这些文件里的手改也会一起被覆盖。',
      '恢复内置主题',
      { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  themeReseeding.value = true
  try {
    themes.value = await themeApi.reseed()
    ElMessage.success('内置主题已恢复')
  } finally {
    themeReseeding.value = false
  }
}

onMounted(() => {
  void load()
  void loadThemes()
  void loadMail()
})
</script>

<template>
  <div v-loading="loading" style="display: grid; gap: 16px">
    <div class="md-card-panel">
      <div class="md-panel-head">
        <h2>站点品牌</h2>
        <span class="md-ad-chip">全部署生效</span>
      </div>
      <div class="md-panel-body">
        <div class="md-brand-assets">
          <section class="md-brand-asset">
            <span class="md-brand-asset__preview is-icon">
              <img :src="iconSrc || DEFAULT_FAVICON" alt="站点图标" />
            </span>
            <div class="md-brand-asset__body">
              <div class="md-brand-asset__head">
                <span class="md-brand-asset__title">站点图标</span>
                <HelpTip :width="340">
                  用于浏览器标签页；没有上传横版 Logo 时，也作为顶栏、侧栏、登录页与页脚的品牌图标。
                  建议正方形 PNG / SVG，不超过 2MB；未上传时使用内置圆形标记。
                </HelpTip>
              </div>
              <p class="md-brand-asset__hint">建议 128×128 以上的正方形图片。</p>
              <div class="md-brand-asset__actions">
                <el-button :loading="uploadingIcon" @click="pickIcon">
                  <MdIcon name="upload" :size="14" />{{ iconSrc ? '更换图标' : '上传图标' }}
                </el-button>
                <el-button v-if="iconSrc" type="danger" plain @click="removeIcon">
                  <MdIcon name="trash" :size="14" />移除
                </el-button>
              </div>
            </div>
          </section>

          <section class="md-brand-asset">
            <span class="md-brand-asset__preview is-logo" :style="logoPreviewStyle">
              <img :src="logoSrc || DEFAULT_LOGO" alt="站点 Logo" />
            </span>
            <div class="md-brand-asset__body">
              <div class="md-brand-asset__head">
                <span class="md-brand-asset__title">横版 Logo</span>
                <HelpTip :width="340">
                  优先用于站内品牌位（顶栏、侧栏、登录页、页脚、分享页），按原比例完整显示、不会裁切。
                  支持 PNG / JPG / SVG / WebP，不超过 2MB；未上传时用站点图标与名称显示。
                </HelpTip>
              </div>
              <p class="md-brand-asset__hint">适合「图标 + 站名」的组合图，建议保留透明背景。</p>
              <div class="md-brand-asset__actions">
                <el-button :loading="uploading" @click="pickLogo">
                  <MdIcon name="upload" :size="14" />{{ logoSrc ? '更换 Logo' : '上传 Logo' }}
                </el-button>
                <el-button v-if="logoSrc" type="danger" plain @click="removeLogo">
                  <MdIcon name="trash" :size="14" />移除
                </el-button>
              </div>
            </div>
          </section>
        </div>
        <input ref="iconFileRef" type="file" accept="image/*" hidden @change="onIconFile" />
        <input ref="fileRef" type="file" accept="image/*" hidden @change="onFile" />

        <el-divider class="md-brand-divider" />

        <el-form label-position="top" class="md-brand-form">
          <div class="md-brand-form__grid">
            <el-form-item>
              <template #label>显示名称<HelpTip text="名称与副标题出现在门户顶栏、阅读页、分享页与后台侧栏；留空则回落到内置默认品牌。" /></template>
              <el-input v-model="form.name" maxlength="32" show-word-limit placeholder="MiniDocs" />
            </el-form-item>
            <el-form-item label="副标题">
              <el-input v-model="form.subtitle" maxlength="48" show-word-limit placeholder="极简智库" />
            </el-form-item>
            <el-form-item>
              <template #label>Logo 宽度（像素）<HelpTip :width="340">
                只作用于横版 Logo，留空即跟随各品牌位默认尺寸（顶栏 34、后台侧栏 32、登录页 44）；
                只填一边时另一边保持默认，图片按原比例完整显示。页脚固定 26px，站点图标不受影响。
              </HelpTip></template>
              <el-input-number
                v-model="logoForm.width"
                :min="16"
                :max="512"
                :value-on-clear="null"
                controls-position="right"
                placeholder="默认"
              />
            </el-form-item>
            <el-form-item label="Logo 高度（像素）">
              <el-input-number
                v-model="logoForm.height"
                :min="16"
                :max="512"
                :value-on-clear="null"
                controls-position="right"
                placeholder="默认"
              />
            </el-form-item>
            <el-form-item>
              <template #label>显示内容<HelpTip :width="340">
                「只显示图片」会隐藏各品牌位的名称与副标题；还没上传 Logo 或站点图标时不可开启，
                否则品牌位什么都不剩。有横版 Logo 时显示 Logo，否则显示站点图标。
              </HelpTip></template>
              <el-switch
                v-model="logoForm.only"
                :disabled="!logoSrc && !iconSrc"
                active-text="只显示图片"
                inactive-text="连同名称一起"
              />
            </el-form-item>
          </div>
          <div class="md-brand-form__footer">
            <el-button type="primary" :loading="saving" @click="saveBrand">
              <MdIcon name="save" :size="14" />保存品牌设置
            </el-button>
          </div>
        </el-form>
      </div>
    </div>

    <div class="md-card-panel">
      <div class="md-panel-head">
        <h2>分享链接</h2>
        <span class="md-ad-chip">全部署生效</span>
      </div>
      <div class="md-panel-body">
        <el-form label-position="top" style="max-width: 560px">
          <el-form-item :error="baseUrlError">
            <template #label>站点基址<HelpTip :width="360">
              站点基址是分享链接的地址前缀（协议 + 域名，可带路径如 <code>/minidocs</code>），改完立即对所有新分享生效。
              留空则回落到部署配置 <code>PAGE_BASE_URL</code>，再没有就按当前请求推导 ——
              最后这一层仅适合同源部署，反向代理后面直接用会让链接指向内网地址。
            </HelpTip></template>
            <el-input
              v-model="form.baseUrl"
              maxlength="200"
              placeholder="https://docs.example.com"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="baseUrlSaving" @click="saveBaseUrl">
              <MdIcon name="save" :size="14" />保存
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <div v-loading="mailLoading" class="md-card-panel">
      <div class="md-panel-head">
        <h2>邮件与邮箱验证<HelpTip :width="400">
          填好 SMTP 参数后，这台部署就能把注册验证码发出去。
          口令加密存库、保存后不再回显；不配 SMTP 也可以，只是没法开启邮箱验证。
        </HelpTip></h2>
        <span class="md-ad-chip">全部署生效</span>
      </div>
      <div class="md-panel-body">
        <el-alert
          v-if="mail.smtpCredentialUnusable"
          class="md-mail-alert"
          type="error"
          :closable="false"
          show-icon
          title="已存的 SMTP 口令解不开（多半是加密密钥换过）。请重新填一次口令并保存，否则验证邮件发不出去。"
        />

        <!-- 先讲「要不要验证」，再讲「从哪发」：这一页最常问的是前者 -->
        <section class="md-mail-mode" :class="{ 'is-on': mail.emailVerifyEnabled }">
          <div class="md-mail-mode__text">
            <div class="md-mail-mode__title">
              <h3>注册邮箱验证</h3>
              <span class="md-mail-mode__chip" :class="`is-${verificationChip.tone}`">{{ verificationChip.text }}</span>
              <HelpTip :width="400">
                开启后，注册页会要求先填邮箱收到的 6 位验证码，填对才建号；平台管理员不受这条限制，以免把自己锁在门外。
                发件参数不齐时这一项不会生效：验证码发不出去还强制验证，等于谁都注册不了。
                之后在账号设置里改邮箱，仍然走邮件里的验证链接。
              </HelpTip>
            </div>
            <p>{{ verificationSummary }}</p>
          </div>
          <el-switch v-model="mailForm.emailVerifyEnabled" active-text="要求先验证邮箱" />
        </section>

        <section class="md-mail-block">
          <header class="md-mail-block__head">
            <h3><MdIcon name="globe" :size="14" />发送服务器</h3>
            <p>验证邮件由这台机器直接投递；改完先保存，再往里发一封测试信确认。</p>
          </header>
          <el-form label-position="top" class="md-mail-form">
            <div class="md-mail-grid">
              <el-form-item>
                <template #label>SMTP 主机<HelpTip :width="340" text="只填域名，例如 smtp.qq.com；不要带 https:// 前缀，端口单独填在下面。" /></template>
                <el-input v-model="mailForm.smtpHost" maxlength="128" placeholder="smtp.example.com" />
              </el-form-item>
              <el-form-item>
                <template #label>SMTP 端口<HelpTip :width="340" text="常见为 465（SSL 直连）或 587（STARTTLS 升级）；留空表示不加密中继的 25。" /></template>
                <el-input-number
                  v-model="mailForm.smtpPort"
                  :min="1"
                  :max="65535"
                  :value-on-clear="null"
                  controls-position="right"
                  placeholder="465"
                />
              </el-form-item>
              <el-form-item :label="securityAutoHint ? `加密方式（当前：${securityAutoHint}）` : '加密方式'">
                <el-select v-model="mailForm.smtpSecurity" placeholder="自动（按端口推断）">
                  <el-option label="自动（按端口推断）" value="" />
                  <el-option label="SSL 直连" value="ssl" />
                  <el-option label="STARTTLS 升级" value="starttls" />
                  <el-option label="不加密" value="none" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <template #label>SMTP 用户名<HelpTip :width="360">
                  登录 SMTP 的账号，多数服务商就是发件邮箱本身。<strong>要和下面的口令一起填</strong>；
                  只有不需要认证的内网中继才把这一项留空（只填用户名不填口令，服务器会回 503 need EHLO and AUTH first）。
                </HelpTip></template>
                <el-input v-model="mailForm.smtpUsername" maxlength="128" placeholder="完整邮箱地址，可留空" />
              </el-form-item>
              <el-form-item>
                <template #label>发件地址<HelpTip :width="360" text="验证邮件的 From。很多服务商要求它和登录用户名一致，否则会以 550 拒发。" /></template>
                <el-input v-model="mailForm.smtpFrom" maxlength="128" placeholder="no-reply@example.com" />
              </el-form-item>
              <el-form-item>
                <template #label>SMTP 口令<HelpTip :width="380">
                  这里填服务商给的<strong>授权码 / 应用专用密码</strong>，不是邮箱登录密码（QQ、163 都要先在邮箱设置里单独开）。
                  保存后不再回显；留空即沿用已存的那一个，要删除已存口令点下面的「清除已存口令」。
                </HelpTip></template>
                <div class="md-mail-password">
                  <el-input
                    v-model="mailForm.smtpPassword"
                    type="password"
                    show-password
                    maxlength="256"
                    :placeholder="mail.smtpPasswordSet ? '已配置，留空则不修改' : '登录 SMTP 的授权码'"
                  />
                  <el-button
                    v-if="mail.smtpPasswordSet && !clearMailPassword && !mailForm.smtpPassword"
                    link
                    type="danger"
                    @click="clearMailPassword = true"
                  >
                    清除已存口令
                  </el-button>
                  <span v-else-if="clearMailPassword && !mailForm.smtpPassword" class="md-mail-password__clear">
                    保存后会删除已存口令
                    <el-button link @click="clearMailPassword = false">取消</el-button>
                  </span>
                </div>
              </el-form-item>
            </div>
          </el-form>
        </section>

        <div class="md-mail-actions">
          <el-button type="primary" :loading="mailSaving" @click="saveMail">
            <MdIcon name="save" :size="14" />保存邮件配置
          </el-button>
          <span class="md-mail-actions__spacer" />
          <span class="md-mail-test">
            <el-input
              v-model="mailTestTo"
              class="md-mail-test__to"
              maxlength="128"
              placeholder="收件地址（自己的邮箱）"
            />
            <el-button :loading="mailTesting" @click="sendTestMail">
              <MdIcon name="mail" :size="14" />测试发信
            </el-button>
            <HelpTip :width="360">
              测试用的是<strong>已经保存</strong>的参数，所以改完先点「保存邮件配置」再发。
              发送失败时把邮件服务器给的原因原样带回，那是排查端口、证书、授权码最快的一条路。
            </HelpTip>
          </span>
        </div>
      </div>
    </div>

    <div v-loading="themesLoading" class="md-card-panel">
      <div class="md-panel-head">
        <h2>阅读主题<HelpTip :width="380" text="读者在阅读页 / 分享页的「阅读设置」里挑主题；每套主题自带明亮与暗黑两档，与现有的外观切换相互独立。装好的主题会出现在所有读者的面板里，刷新即生效。点击卡片可把它设为新访客的默认主题：没自己选过主题的访客第一次打开就套用它；读过一次并自己选过的人，仍以读者自己的选择为准。" /></h2>
        <span class="md-ad-chip">读者可选</span>
      </div>
      <div class="md-panel-body">
        <div class="md-theme-grid">
          <div
            v-for="card in themeCards"
            :key="card.pack.id"
            class="md-theme-card"
            :class="{ 'is-default': isDefaultTheme(card.pack), 'is-saving': readerThemeSaving }"
            role="button"
            tabindex="0"
            :aria-pressed="isDefaultTheme(card.pack)"
            @click="setDefaultTheme(card.pack)"
            @keydown.enter.prevent="setDefaultTheme(card.pack)"
            @keydown.space.prevent="setDefaultTheme(card.pack)"
          >
            <div class="md-theme-card__swatch" aria-hidden="true">
              <span class="md-theme-card__half" :style="{ background: card.light.bg }">
                <i class="md-theme-card__bar" :style="{ background: card.light.primary }"></i>
                <i class="md-theme-card__bar is-thin" :style="{ background: card.light.accent }"></i>
              </span>
              <span class="md-theme-card__half is-dark" :style="{ background: card.dark.bg }">
                <i class="md-theme-card__bar" :style="{ background: card.dark.primary }"></i>
                <i class="md-theme-card__bar is-thin" :style="{ background: card.dark.accent }"></i>
              </span>
            </div>
            <div class="md-theme-card__head">
              <strong class="md-theme-card__name">{{ card.pack.name }}</strong>
              <span
                class="md-theme-card__tag"
                :class="{ 'is-accent': isDefaultTheme(card.pack) }"
              >{{ isDefaultTheme(card.pack) ? '默认主题' : (card.pack.builtin ? '内置' : '自定义') }}</span>
            </div>
            <p class="md-theme-card__desc">{{ card.pack.description || '（无描述）' }}</p>
            <div class="md-theme-card__foot">
              <span class="md-theme-card__meta">
                {{ card.pack.author || '未知作者' }}<template v-if="card.pack.version"> · v{{ card.pack.version }}</template>
              </span>
              <el-button
                type="danger"
                link
                size="small"
                @click.stop="uninstallTheme(card.pack)"
              >
                卸载
              </el-button>
            </div>
          </div>
        </div>

        <div class="md-theme-actions">
          <el-button :loading="themeUploading" @click="pickThemeFile">
            <MdIcon name="upload" :size="14" />安装主题包
          </el-button>
          <el-button :loading="themeReseeding" @click="reseedThemes">
            <MdIcon name="refresh" :size="14" />恢复内置主题
          </el-button>
          <el-button link @click="downloadThemeTemplate">
            <MdIcon name="download" :size="14" />下载模板
          </el-button>
          <HelpTip :width="340">
            主题包是只描述颜色与排版参数的 JSON，不能携带任意 CSS。每档的令牌名必须一致，
            单个文件不超过 64KB；从「下载模板」改起最省事，令牌说明见 <code>docs/THEMES.md</code>。
          </HelpTip>
        </div>
        <input
          ref="themeFileRef"
          type="file"
          accept=".json,application/json"
          hidden
          @change="onThemeFile"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 两种图片并排：图标偏正方形、Logo 偏横向，各自独立成卡，避免「谁替换谁」的误解 */
.md-brand-assets {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px;
}
.md-brand-asset {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--md-border);
  border-radius: var(--md-radius-sm);
  background: var(--md-surface);
}
/* 预览不给底板：与顶栏/侧栏/页脚/登录页同一套裸图口径，否则「预览里有框、线上没框」会让人以为没生效 */
.md-brand-asset__preview {
  width: 72px;
  height: 72px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  grid-template-rows: 100%;
  color: var(--md-text-3);
}
.md-brand-asset__preview.is-icon {
  width: 56px;
  height: 56px;
}
.md-brand-asset__preview.is-logo {
  max-width: 220px;
}
.md-brand-asset__preview img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.md-brand-asset__body {
  min-width: 0;
}
.md-brand-asset__head {
  display: flex;
  align-items: center;
  gap: 2px;
}
.md-brand-asset__title {
  font-size: 14px;
  font-weight: 600;
}
.md-brand-asset__hint {
  margin: 3px 0 0;
  color: var(--md-text-3);
  font-size: 12.5px;
  line-height: 1.55;
}
.md-brand-asset__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
}
.md-brand-divider {
  margin: 16px 0 12px;
}
/* 名称、副标题、尺寸与显示开关排成两列；窄屏自动落成单列 */
.md-brand-form__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 240px));
  gap: 0 16px;
  align-items: start;
}
.md-brand-form__grid :deep(.el-form-item) {
  margin-bottom: 12px;
}
.md-brand-form__footer {
  margin-top: 2px;
}

/* 邮件配置：先「要不要验证」、再「从哪发」，最后才是动作条 —— 三段的层级靠留白与分隔线，不靠加粗标题 */
.md-mail-alert {
  margin-bottom: 12px;
}

/* 验证开关做成一条横卡：开着的时候整块染一点主色，扫一眼就知道这台部署在不在要求验证 */
.md-mail-mode {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 14px 16px;
  border: 1px solid var(--md-border);
  border-radius: 12px;
  background: var(--md-surface-2);
  transition: border-color .15s ease, background-color .15s ease;
}
.md-mail-mode.is-on {
  border-color: color-mix(in srgb, var(--md-primary) 42%, var(--md-border));
  background: color-mix(in srgb, var(--md-primary) 7%, var(--md-surface));
}
.md-mail-mode__text { min-width: 0; }
.md-mail-mode__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.md-mail-mode__title h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--md-text);
}
.md-mail-mode__chip {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 500;
  line-height: 18px;
}
.md-mail-mode__chip.is-on {
  color: var(--md-success);
  background: color-mix(in srgb, var(--md-success) 12%, transparent);
}
.md-mail-mode__chip.is-warn {
  color: var(--md-warning);
  background: color-mix(in srgb, var(--md-warning) 13%, transparent);
}
.md-mail-mode__chip.is-off {
  color: var(--md-text-3);
  background: color-mix(in srgb, var(--md-text-3) 12%, transparent);
}
.md-mail-mode__text p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--md-text-2);
}

/* 发送服务器与开关是同一件事的两半，用虚线段开，不再套第二张卡片 */
.md-mail-block {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--md-border);
}
.md-mail-block__head { margin-bottom: 10px; }
.md-mail-block__head h3 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 3px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--md-text);
}
.md-mail-block__head p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--md-text-3);
}
/* 六项两行三列：主机 / 端口 / 加密，账号 / 发件人 / 口令。
   不跟着容器宽度自动加列 —— 四列时第二行只剩两项，右边留一个空洞，看起来像表单没填完 */
.md-mail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 18px;
  align-items: start;
}
@media (max-width: 1100px) {
  .md-mail-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .md-mail-grid { grid-template-columns: 1fr; }
}
.md-mail-grid :deep(.el-form-item) {
  margin-bottom: 12px;
}
.md-mail-grid :deep(.el-input-number),
.md-mail-grid :deep(.el-select) {
  width: 100%;
}
/* 口令一格放两样东西：输入框占满，清除口令是它下面的一句轻动作，不抢主按钮的位置 */
.md-mail-password {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  width: 100%;
}
.md-mail-password__clear {
  color: var(--md-text-3);
  font-size: 12px;
}

/* 动作条：保存是这一页唯一的主动作，测试发信靠右隔开 —— 两颗挨着时点错一次就是白等一封邮件 */
.md-mail-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 6px;
  padding-top: 16px;
  border-top: 1px solid var(--md-border);
}
.md-mail-actions__spacer { flex: 1 1 auto; }
.md-mail-test {
  display: flex;
  align-items: center;
  gap: 8px;
}
.md-mail-test__to {
  width: 240px;
}
@media (max-width: 900px) {
  .md-mail-mode {
    flex-direction: column;
    align-items: flex-start;
  }
  .md-mail-actions__spacer { display: none; }
  /* 这一档还要挤在一行：输入框吃掉剩余宽度，按钮保持自然宽度，别让「测试发信」掉到第三行 */
  .md-mail-test {
    flex: 1 1 320px;
    min-width: 0;
    flex-wrap: nowrap;
  }
  .md-mail-test__to {
    width: auto;
    flex: 1 1 auto;
  }
}

/* 阅读主题：卡片网格 + 明暗双格小样。色值来自主题包本身，不在这里写死 */
.md-theme-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 12px;
  margin: 14px 0;
}
.md-theme-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--md-border);
  border-radius: var(--md-radius-sm);
  background: var(--md-surface);
  cursor: pointer;
  transition: border-color .15s ease, background .15s ease, box-shadow .15s ease;
}
.md-theme-card:hover { border-color: color-mix(in srgb, var(--md-primary) 45%, var(--md-border)); }
.md-theme-card:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--md-primary) 55%, transparent);
  outline-offset: 2px;
}
/* 选中 = 新访客的默认主题：主色描边 + 极浅主色底，点击目标一眼可辨 */
.md-theme-card.is-default {
  border-color: var(--md-primary);
  background: color-mix(in srgb, var(--md-primary) 4%, var(--md-surface));
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--md-primary) 30%, transparent);
}
.md-theme-card.is-saving { cursor: progress; }
.md-theme-card__swatch {
  display: flex;
  height: 46px;
  border-radius: 7px;
  overflow: hidden;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, .08);
}
.md-theme-card__half {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 3px;
  padding: 8px;
}
.md-theme-card__half.is-dark { border-left: 1px solid rgba(255, 255, 255, .16); }
.md-theme-card__bar { height: 4px; border-radius: 2px; }
.md-theme-card__bar.is-thin { width: 62%; height: 3px; opacity: .8; }
.md-theme-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.md-theme-card__name {
  font-size: 14px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.md-theme-card__tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 999px;
  border: 1px solid var(--md-border);
  color: var(--md-text-3);
  font-size: 11px;
}
.md-theme-card__tag.is-accent {
  border-color: color-mix(in srgb, var(--md-primary) 55%, transparent);
  color: var(--md-primary);
  background: var(--md-primary-soft);
}
.md-theme-card__desc {
  flex: 1;
  margin: 0;
  color: var(--md-text-2);
  font-size: 12.5px;
  line-height: 1.6;
}
.md-theme-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 24px;
}
.md-theme-card__meta {
  color: var(--md-text-3);
  font-size: 11.5px;
}
.md-theme-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
</style>
