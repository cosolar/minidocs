<script setup lang="ts">
/**
 * 账号设置：资料、密码、外观偏好。
 *
 * <p>它刻意<b>不带组织段</b>（{@code /account} 而不是 {@code /console/{org}/settings}）：这三样东西都属于
 * 人，不属于组织，放在组织下面会让人误以为「换个组织设置不同」，而且从阅读页顶栏点进来时
 * 并没有一个自然的 org 可填。组织自己的资料与成员治理在 {@code OrgSettingsView}。</p>
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi, avatarApi } from '@/admin/api'
import MdIcon from '@/admin/components/MdIcon.vue'
import { appBackendHref, appHref } from '@/shared/appBase'
import { useAuthStore } from '@/admin/stores/auth'
import { useSettingsStore } from '@/shared/stores/settings'

const auth = useAuthStore()
const settings = useSettingsStore()

const account = reactive({ username: '', displayName: '', email: '' })
const password = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const accountSaving = ref(false)
const passwordSaving = ref(false)

/* ------------------------------------------------------------------ 头像 */
const AVATAR_MAX_SIZE = 2 * 1024 * 1024
const avatarInput = ref<HTMLInputElement | null>(null)
const avatarUploading = ref(false)
const avatarRemoving = ref(false)

function pickAvatar() {
  avatarInput.value?.click()
}

async function onAvatarChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 先清空，否则连续选同一个文件不会再触发 change
  input.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  if (file.size > AVATAR_MAX_SIZE) {
    ElMessage.warning('图片不能超过 2MB')
    return
  }
  avatarUploading.value = true
  try {
    auth.setUser(await avatarApi.upload(file))
    ElMessage.success('头像已更新')
  } finally {
    avatarUploading.value = false
  }
}

async function removeAvatar() {
  avatarRemoving.value = true
  try {
    auth.setUser(await avatarApi.remove())
    ElMessage.success('头像已移除')
  } finally {
    avatarRemoving.value = false
  }
}

const codeThemes = [
  { label: 'GitHub Light', value: 'github' },
  { label: 'GitHub Dark', value: 'github-dark' },
  { label: 'Atom One Light', value: 'atom-one-light' },
  { label: 'Atom One Dark', value: 'atom-one-dark' },
  { label: 'Monokai', value: 'monokai' },
  { label: 'Nord', value: 'nord' }
]

async function saveAccount() {
  accountSaving.value = true
  try {
    await auth.updateAccount({
      username: account.username || undefined,
      displayName: account.displayName,
      email: account.email || undefined
    })
    ElMessage.success('资料已更新')
  } finally {
    accountSaving.value = false
  }
}

async function savePassword() {
  if (password.newPassword !== password.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  if (password.newPassword.length < 6) {
    ElMessage.warning('新密码长度至少 6 位')
    return
  }
  passwordSaving.value = true
  try {
    await authApi.updatePassword({ oldPassword: password.oldPassword, newPassword: password.newPassword })
    password.oldPassword = ''
    password.newPassword = ''
    password.confirm = ''
    ElMessage.success('密码已修改')
  } finally {
    passwordSaving.value = false
  }
}

async function patch(patchValue: Record<string, unknown>) {
  await settings.patch(patchValue)
}

onMounted(async () => {
  settings.applyToDom()
  if (!auth.user) await auth.loadProfile()
  account.username = auth.user?.username || ''
  account.displayName = auth.user?.displayName || ''
  account.email = auth.user?.email || ''
  await settings.load()
})
</script>

<template>
  <div style="display: grid; gap: 16px">
    <div class="md-card-panel">
      <div class="md-panel-head"><h2>头像</h2></div>
      <div class="md-panel-body">
        <div class="md-avatar-field">
          <span class="md-avatar-field__preview">
            <img v-if="auth.avatarSrc" :src="auth.avatarSrc" :alt="auth.nickname" />
            <template v-else>{{ (auth.nickname || 'U').slice(0, 1) }}</template>
          </span>
          <div class="md-avatar-field__side">
            <div class="md-avatar-field__actions">
              <el-button type="primary" :loading="avatarUploading" @click="pickAvatar">
                <MdIcon name="upload" :size="14" />上传头像
              </el-button>
              <el-button :disabled="!auth.avatarSrc" :loading="avatarRemoving" @click="removeAvatar">
                <MdIcon name="trash" :size="14" />移除
              </el-button>
            </div>
            <p class="md-copy-hint">支持 PNG / JPG / GIF / WebP / SVG，不超过 2MB；建议使用正方形图片。</p>
          </div>
        </div>
        <input ref="avatarInput" type="file" accept="image/*" hidden @change="onAvatarChange" />
      </div>
    </div>

    <div class="md-card-panel">
      <div class="md-panel-head"><h2>账号资料</h2></div>
      <div class="md-panel-body">
        <el-form label-position="top" style="max-width: 520px">
          <el-form-item label="用户名（3-32 位，字母 / 数字 / 下划线，全局唯一）">
            <el-input v-model="account.username" maxlength="32" />
          </el-form-item>
          <el-form-item label="显示名">
            <el-input v-model="account.displayName" maxlength="64" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="account.email" maxlength="128" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="accountSaving" @click="saveAccount">
              <MdIcon name="save" :size="14" />保存资料
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <div class="md-card-panel">
      <div class="md-panel-head"><h2>修改密码</h2></div>
      <div class="md-panel-body">
        <el-form label-position="top" style="max-width: 520px">
          <el-form-item label="原密码">
            <el-input v-model="password.oldPassword" type="password" show-password />
          </el-form-item>
          <el-form-item label="新密码（6-64 位）">
            <el-input v-model="password.newPassword" type="password" show-password />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="password.confirm" type="password" show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="passwordSaving" @click="savePassword">
              <MdIcon name="key" :size="14" />修改密码
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <div class="md-card-panel">
      <div class="md-panel-head"><h2>外观与偏好</h2></div>
      <div class="md-panel-body">
        <el-form label-position="top" style="max-width: 620px">
          <el-form-item label="主题模式">
            <el-radio-group :model-value="settings.value.themeMode" @change="(value) => patch({ themeMode: value })">
              <el-radio-button value="light">浅色</el-radio-button>
              <el-radio-button value="dark">深色</el-radio-button>
              <el-radio-button value="system">跟随系统</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="代码块主题">
            <el-select
              :model-value="settings.value.codeTheme"
              style="width: 220px"
              @change="(value) => patch({ codeTheme: value })"
            >
              <el-option v-for="theme in codeThemes" :key="theme.value" :label="theme.label" :value="theme.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="阅读区最大宽度">
            <el-slider
              :model-value="settings.value.contentWidth"
              :min="720"
              :max="1400"
              :step="20"
              show-input
              style="max-width: 460px"
              @change="(value) => patch({ contentWidth: value })"
            />
          </el-form-item>
          <el-form-item label="知识库列表默认视图">
            <el-radio-group :model-value="settings.value.kbView" @change="(value) => patch({ kbView: value })">
              <el-radio-button value="grid">网格</el-radio-button>
              <el-radio-button value="list">列表</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="侧边栏默认展开">
            <el-switch
              :model-value="settings.value.showDocTree"
              @change="(value) => patch({ showDocTree: value })"
            />
          </el-form-item>
          <el-form-item label="阅读页默认视图">
            <el-radio-group :model-value="settings.value.defaultView" @change="(value) => patch({ defaultView: value })">
              <el-radio-button value="read">阅读</el-radio-button>
              <el-radio-button value="outline">大纲优先</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </el-form>
        <p class="md-copy-hint">偏好保存在服务端（user_settings），换设备登录后自动同步。</p>
      </div>
    </div>

    <div class="md-card-panel">
      <div class="md-panel-head"><h2>关于</h2></div>
      <div class="md-panel-body">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="版本">MiniDocs 1.2.0</el-descriptions-item>
          <el-descriptions-item label="接口文档">
            <!-- swagger 由后端伺服，要带 context-path；页面基址是根，两者不是同一段 -->
            <a :href="appBackendHref('/swagger-ui.html')" target="_blank" style="color: var(--md-primary)">/swagger-ui.html</a>
          </el-descriptions-item>
          <el-descriptions-item label="门户首页">
            <a :href="appHref('/')" target="_blank" style="color: var(--md-primary)">/</a>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </div>
  </div>
</template>
