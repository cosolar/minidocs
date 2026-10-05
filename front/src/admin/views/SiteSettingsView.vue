<script setup lang="ts">
/**
 * 站点设置：品牌名称 / 副标题 / Logo（路由 {@code /platform/site}）。
 *
 * <p>这一页改的是「整个部署」的品牌，不是某个组织、也不是某个人：所以它不带组织段，
 * 门槛是平台角色（路由 meta.platformAdmin + 后端 {@code /api/platform/site} 的 AdminInterceptor）。
 * 保存成功后把出参回灌共享 site store，顶栏、侧栏、页面标题立即跟着变，不必刷新。</p>
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { siteApi } from '@/admin/api'
import MdIcon from '@/admin/components/MdIcon.vue'
import { useSiteStore } from '@/shared/stores/site'

const site = useSiteStore()

const loading = ref(true)
const saving = ref(false)
const uploading = ref(false)
const fileRef = ref<HTMLInputElement | null>(null)

const form = reactive({ name: '', subtitle: '' })

/** 预览优先用刚保存下来的地址；还没保存过的本地选择不做即时预览，避免与后端真值不一致 */
const logoSrc = computed(() => site.value.logoSrc || '')

async function load() {
  loading.value = true
  try {
    const data = await siteApi.get()
    site.apply(data)
    form.name = data.name || ''
    form.subtitle = data.subtitle || ''
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('站点名称不能为空')
    return
  }
  saving.value = true
  try {
    const data = await siteApi.update({ name: form.name.trim(), subtitle: form.subtitle.trim() })
    site.apply(data)
    form.name = data.name
    form.subtitle = data.subtitle || ''
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

onMounted(load)
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
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">
              <MdIcon name="save" :size="14" />保存
            </el-button>
          </el-form-item>
        </el-form>
        <p class="md-copy-hint">
          名称与副标题出现在门户顶栏、阅读页、分享页与后台侧栏；留空则回落到内置默认品牌。
        </p>
      </div>
    </div>

    <div class="md-card-panel">
      <div class="md-panel-head"><h2>站点 Logo</h2></div>
      <div class="md-panel-body">
        <div class="md-site-logo">
          <span class="md-site-logo__preview">
            <img v-if="logoSrc" :src="logoSrc" alt="站点 Logo" />
            <MdIcon v-else name="image" :size="22" />
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
</style>
