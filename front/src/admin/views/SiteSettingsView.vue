<script setup lang="ts">
/**
 * 站点设置：品牌名称 / 副标题 / Logo / 站点基址（路由 {@code /platform/site}）。
 *
 * <p>这一页改的是「整个部署」的品牌，不是某个组织、也不是某个人：所以它不带组织段，
 * 门槛是平台角色（路由 meta.platformAdmin + 后端 {@code /api/platform/site} 的 AdminInterceptor）。
 * 保存成功后把出参回灌共享 site store，顶栏、侧栏、页面标题立即跟着变，不必刷新。</p>
 *
 * <p>站点基址（{@code baseUrl}）管的是分享链接：改了之后所有新复制出去的链接立即跟着变。
 * 留空则回落到部署配置 {@code PAGE_BASE_URL}，再没有就按当前请求推导 ——
 * 最后这一层要求同源部署，反代后面直接用会让链接指向内网地址。</p>
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { siteApi, themeApi } from '@/admin/api'
import MdIcon from '@/admin/components/MdIcon.vue'
import { useSiteStore } from '@/shared/stores/site'
import { DEFAULT_LOGO } from '@/shared/brand'
import { DEFAULT_THEME, themeSwatch, type ThemePack } from '@/shared/readerTheme'
import { downloadThemeTemplate } from '@/shared/themeTemplate'

const site = useSiteStore()

const loading = ref(true)
const saving = ref(false)
const uploading = ref(false)
const fileRef = ref<HTMLInputElement | null>(null)

const form = reactive({ name: '', subtitle: '', baseUrl: '' })

/** 预览优先用刚保存下来的地址；还没保存过的本地选择不做即时预览，避免与后端真值不一致 */
const logoSrc = computed(() => site.value.logoSrc || '')

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
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('站点名称不能为空')
    return
  }
  if (baseUrlError.value) {
    ElMessage.warning(baseUrlError.value)
    return
  }
  saving.value = true
  try {
    const data = await siteApi.update({
      name: form.name.trim(),
      subtitle: form.subtitle.trim(),
      baseUrl: form.baseUrl.trim()
    })
    site.apply(data)
    form.name = data.name
    form.subtitle = data.subtitle || ''
    // 回填后端归一化过的值（补上的 https://、去掉的尾斜杠），别让输入框里留着一段需要人脑补的字符串
    form.baseUrl = data.baseUrl || ''
    ElMessage.success('站点信息已更新')
  } finally {
    saving.value = false
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

/* ------------------------------------------------------------------ 阅读主题 */
/*
 * 主题包是「服务器侧的文件」：装着几张 JSON，读者的浏览器拉列表后自己拼样式。
 * 所以这里不需要构建或发布前端 —— 装上刷新生效，卸载同理。默认主题不在文件里，
 * 它由样式表本身提供，列出来只为让管理员看到「可选项里还有它」，不提供卸载。
 */
const themes = ref<ThemePack[]>([])
const themesLoading = ref(false)
const themeUploading = ref(false)
const themeReseeding = ref(false)
const themeFileRef = ref<HTMLInputElement | null>(null)

const themeCards = computed(() =>
  [DEFAULT_THEME, ...themes.value].map((pack) => ({
    pack,
    light: themeSwatch(pack, 'light'),
    dark: themeSwatch(pack, 'dark')
  }))
)

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
  try {
    await ElMessageBox.confirm(
      `卸载后读者将看不到「${pack.name}」；已经选了它的浏览器会自动回落到默认主题。`,
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
})
</script>

<template>
  <div v-loading="loading" style="display: grid; gap: 16px">
    <div class="md-card-panel">
      <div class="md-panel-head">
        <h2>站点信息</h2>
        <span class="md-ad-chip">全部署生效</span>
      </div>
      <div class="md-panel-body">
        <el-form label-position="top" style="max-width: 560px">
          <el-form-item label="站点名称">
            <el-input v-model="form.name" maxlength="32" show-word-limit placeholder="MiniDocs" />
          </el-form-item>
          <el-form-item label="副标题">
            <el-input v-model="form.subtitle" maxlength="48" show-word-limit placeholder="极简知识库" />
          </el-form-item>
          <el-form-item label="站点基址" :error="baseUrlError">
            <el-input
              v-model="form.baseUrl"
              maxlength="200"
              placeholder="https://docs.example.com"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">
              <MdIcon name="save" :size="14" />保存
            </el-button>
          </el-form-item>
        </el-form>
        <p class="md-copy-hint">
          名称与副标题出现在门户顶栏、阅读页、分享页与后台侧栏；留空则回落到内置默认品牌。
        </p>
        <p class="md-copy-hint">
          站点基址是分享链接的地址前缀（协议 + 域名，可带路径如
          <code>/minidocs</code>），改完立即对所有新分享生效。
          留空则回落到部署配置 <code>PAGE_BASE_URL</code>，再没有就按当前请求推导 ——
          最后这一层仅适合同源部署，反向代理后面直接用会让链接指向内网地址。
        </p>
      </div>
    </div>

    <div class="md-card-panel">
      <div class="md-panel-head"><h2>站点 Logo</h2></div>
      <div class="md-panel-body">
        <div class="md-site-logo">
          <span class="md-site-logo__preview">
            <img :src="logoSrc || DEFAULT_LOGO" alt="站点 Logo" />
          </span>
          <div class="md-site-logo__meta">
            <div class="md-site-logo__title">品牌图标</div>
            <p class="md-copy-hint">建议正方形，支持 PNG / JPG / SVG / WebP，不超过 2MB；未上传时使用内置图标。</p>
            <div class="md-site-logo__actions">
              <el-button :loading="uploading" @click="pickLogo">
                <MdIcon name="upload" :size="14" />{{ logoSrc ? '更换 Logo' : '上传 Logo' }}
              </el-button>
              <el-button v-if="logoSrc" type="danger" plain @click="removeLogo">
                <MdIcon name="trash" :size="14" />移除
              </el-button>
            </div>
          </div>
        </div>
        <input ref="fileRef" type="file" accept="image/*" hidden @change="onFile" />
        <p class="md-copy-hint">
          Logo 同时会用作浏览器标签页图标（favicon）。换图即换地址，浏览器不必再做缓存失效判断。
        </p>
      </div>
    </div>

    <div v-loading="themesLoading" class="md-card-panel">
      <div class="md-panel-head">
        <h2>阅读主题</h2>
        <span class="md-ad-chip">读者可选</span>
      </div>
      <div class="md-panel-body">
        <p class="md-copy-hint">
          读者在阅读页 / 分享页的「阅读设置」里挑主题；每套主题自带明亮与暗黑两档，
          与现有的外观切换相互独立。装好的主题会出现在所有读者的面板里，刷新即生效。
        </p>

        <div class="md-theme-grid">
          <div v-for="card in themeCards" :key="card.pack.id" class="md-theme-card">
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
              <span class="md-theme-card__tag">{{ card.pack.id === DEFAULT_THEME.id ? '默认' : (card.pack.builtin ? '内置' : '自定义') }}</span>
            </div>
            <p class="md-theme-card__desc">{{ card.pack.description || '（无描述）' }}</p>
            <div class="md-theme-card__foot">
              <span class="md-theme-card__meta">
                {{ card.pack.author || '未知作者' }}<template v-if="card.pack.version"> · v{{ card.pack.version }}</template>
              </span>
              <el-button
                v-if="card.pack.id !== DEFAULT_THEME.id"
                type="danger"
                link
                size="small"
                @click="uninstallTheme(card.pack)"
              >
                卸载
              </el-button>
              <span v-else class="md-theme-card__meta">不可卸载</span>
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
        </div>
        <input
          ref="themeFileRef"
          type="file"
          accept=".json,application/json"
          hidden
          @change="onThemeFile"
        />

        <p class="md-copy-hint">
          主题包是只描述颜色与排版参数的 JSON，不能携带任意 CSS。每档的令牌名必须一致，
          单个文件不超过 64KB；从「下载模板」改起最省事，令牌说明见
          <code>docs/THEMES.md</code>。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.md-site-logo {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.md-site-logo__preview {
  width: 72px;
  height: 72px;
  border-radius: 16px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  overflow: hidden;
  color: var(--md-text-3);
  background: var(--md-surface-2);
  border: 1px solid var(--md-border);
}
.md-site-logo__preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.md-site-logo__meta {
  min-width: 0;
}
.md-site-logo__title {
  font-size: 14px;
  font-weight: 600;
}
.md-site-logo__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
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
}
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
