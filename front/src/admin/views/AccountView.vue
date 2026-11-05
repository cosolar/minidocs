<script setup lang="ts">
/**
 * 账号设置：资料、密码、外观偏好。
 *
 * <p>它刻意<b>不带组织段</b>（{@code /account} 而不是 {@code /console/{org}/settings}）：这三样东西都属于
 * 人，不属于组织，放在组织下面会让人误以为「换个组织设置不同」，而且从阅读页顶栏点进来时
 * 并没有一个自然的 org 可填。组织自己的资料与成员治理在 {@code OrgSettingsView}。</p>
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi, avatarApi } from '@/admin/api'
import AdminPageHeader from '@/admin/components/AdminPageHeader.vue'
import MdIcon from '@/admin/components/MdIcon.vue'
import HelpTip from '@/shared/components/HelpTip.vue'
import { appBackendHref, appHref } from '@/shared/appBase'
import { toApiErrorInfo } from '@/shared/api/http'
import { useAuthStore } from '@/admin/stores/auth'
import { useSettingsStore } from '@/shared/stores/settings'

const auth = useAuthStore()
const settings = useSettingsStore()

const account = reactive({ username: '', displayName: '', email: '' })
const password = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const accountSaving = ref(false)
const passwordSaving = ref(false)
const verifySending = ref(false)
/** 后端同一账号 60 秒只发一次；按钮同步倒计时，别让人靠反复点击去试出这条规则 */
const verifyCooldown = ref(0)
let verifyTimer: ReturnType<typeof setInterval> | undefined

/**
 * 邮箱这一栏现在要不要催人去验证。
 *
 * <p>只看后端回吐的 {@code emailVerified}：它是库里的原值，而「登录时会不会被挡」正是由这一列
 * 与开关共同决定。前端不再判一次开关，免得出现「界面说没事、登录说有事」。</p>
 */
const emailUnverified = computed(() => auth.user?.emailVerified === false)

function startVerifyCooldown() {
  verifyCooldown.value = 60
  if (verifyTimer) clearInterval(verifyTimer)
  verifyTimer = setInterval(() => {
    verifyCooldown.value -= 1
    if (verifyCooldown.value <= 0) {
      clearInterval(verifyTimer)
      verifyTimer = undefined
    }
  }, 1000)
}

async function sendOwnVerification() {
  if (verifyCooldown.value > 0) return
  verifySending.value = true
  try {
    await authApi.resendOwnVerification()
    startVerifyCooldown()
    ElMessage.success('验证邮件已发送，60 秒后可重新发送；没收到请检查垃圾邮件箱')
  } catch (error) {
    // 后端已经发过一封时，把剩余的等待时间显式摆在按钮上
    if (toApiErrorInfo(error).code === 42903) startVerifyCooldown()
  } finally {
    verifySending.value = false
  }
}

onBeforeUnmount(() => {
  if (verifyTimer) clearInterval(verifyTimer)
})

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
  // 默认项：代码配色跟着读者选的阅读主题走（见 shared/readerTheme.ts 的单列代码令牌）
  { label: '跟随阅读主题', value: 'theme' },
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
    const updated = await auth.updateAccount({
      username: account.username || undefined,
      displayName: account.displayName,
      email: account.email || undefined
    })
    // 站点开启验证时，改邮箱等于把「已验证」那个结论作废（旧的才是验证过的那个），
    // 后端会顺手往新地址再发一封。这句必须说出口，否则人以为改完就生效。
    if (updated.emailVerified === false) {
      ElMessage.success('资料已更新，验证邮件已发往新邮箱，点开链接后这个地址才归你')
    } else {
      ElMessage.success('资料已更新')
    }
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
  <div class="md-page">
    <AdminPageHeader
      eyebrow="个人"
      title="账号设置"
      description="管理个人资料、登录密码与阅读偏好；偏好会跟随账号同步，换设备登录后自动生效。"
    />

    <section class="md-card-panel">
      <div class="md-panel-body">
        <div class="md-account-hero">
          <span class="md-account-hero__avatar">
            <img v-if="auth.avatarSrc" :src="auth.avatarSrc" alt="" />
            <template v-else>{{ (auth.nickname || 'U').slice(0, 1) }}</template>
          </span>
          <div class="md-account-hero__main">
            <div class="md-account-hero__name">{{ auth.nickname || '未设置显示名' }}</div>
            <div class="md-account-hero__meta">
              <span>@{{ auth.user?.username }}</span>
              <span class="md-account-hero__dot" />
              <span>{{ auth.isPlatformAdmin ? '平台管理员' : '普通用户' }}</span>
              <span v-if="emailUnverified" class="md-ad-chip md-ad-chip--owner">邮箱未验证</span>
            </div>
          </div>
          <div class="md-account-hero__actions">
            <HelpTip text="支持 PNG / JPG / GIF / WebP / SVG，不超过 2MB；建议使用正方形图片。" />
            <el-button type="primary" :loading="avatarUploading" @click="pickAvatar">
              <MdIcon name="upload" :size="14" />上传头像
            </el-button>
            <el-button :disabled="!auth.avatarSrc" :loading="avatarRemoving" @click="removeAvatar">
              <MdIcon name="trash" :size="14" />移除
            </el-button>
          </div>
        </div>
        <input ref="avatarInput" type="file" accept="image/*" hidden @change="onAvatarChange" />
      </div>
    </section>

    <div class="md-settings-grid">
      <div class="md-settings-stack">
        <section class="md-card-panel">
          <div class="md-panel-head">
            <div class="md-panel-head__text">
              <h2>账号资料</h2>
              <p class="md-panel-desc">用户名不能重复；如果平台要求验证邮箱，修改后需要重新验证。</p>
            </div>
          </div>
          <div class="md-panel-body">
            <el-form label-position="top" class="md-form-grid">
              <el-form-item>
                <template #label>用户名<HelpTip :width="320">
                  3-32 位，字母 / 数字 / 下划线，全局唯一。
                </HelpTip></template>
                <el-input v-model="account.username" maxlength="32" />
              </el-form-item>
              <el-form-item label="显示名">
                <el-input v-model="account.displayName" maxlength="64" />
              </el-form-item>
              <el-form-item class="md-form-span-2">
                <template #label>邮箱<HelpTip :width="360">
                  如果平台要求验证邮箱，修改后需要先验证新地址才能继续使用；未开启验证时会立即生效。
                </HelpTip></template>
                <div class="md-account-email">
                  <el-input v-model="account.email" maxlength="128" autocomplete="email" placeholder="alice@example.com" />
                  <span v-if="emailUnverified" class="md-account-email__note">
                    <el-tag type="warning" size="small" effect="light">未验证</el-tag>
                    <el-button link type="primary" :loading="verifySending" :disabled="verifyCooldown > 0" @click="sendOwnVerification">
                      <MdIcon name="mail" :size="13" />{{ verifyCooldown > 0 ? `${verifyCooldown} 秒后可重试` : '发送验证邮件' }}
                    </el-button>
                  </span>
                </div>
              </el-form-item>
              <div class="md-form-footer md-form-span-2">
                <el-button type="primary" :loading="accountSaving" @click="saveAccount">
                  <MdIcon name="save" :size="14" />保存资料
                </el-button>
              </div>
            </el-form>
          </div>
        </section>

        <section class="md-card-panel">
          <div class="md-panel-head">
            <div class="md-panel-head__text">
              <h2>外观与偏好</h2>
              <p class="md-panel-desc">修改后立即保存，无需再点按钮。</p>
            </div>
            <span class="md-spacer" />
            <HelpTip text="偏好会跟随账号同步，换设备登录后自动生效。" />
          </div>
          <div class="md-panel-body">
            <el-form label-position="top" class="md-form-grid">
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
                  style="width: 100%"
                  @change="(value) => patch({ codeTheme: value })"
                >
                  <el-option v-for="theme in codeThemes" :key="theme.value" :label="theme.label" :value="theme.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="阅读区最大宽度" class="md-form-span-2">
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
              <el-form-item label="阅读页默认视图">
                <el-radio-group :model-value="settings.value.defaultView" @change="(value) => patch({ defaultView: value })">
                  <el-radio-button value="read">阅读</el-radio-button>
                  <el-radio-button value="outline">大纲优先</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="侧边栏默认展开" class="md-form-span-2">
                <el-switch
                  :model-value="settings.value.showDocTree"
                  @change="(value) => patch({ showDocTree: value })"
                />
              </el-form-item>
            </el-form>
          </div>
        </section>
      </div>

      <div class="md-settings-stack">
        <section class="md-card-panel">
          <div class="md-panel-head">
            <div class="md-panel-head__text">
              <h2>修改密码</h2>
              <p class="md-panel-desc">改完后请用新密码重新登录其他设备。</p>
            </div>
          </div>
          <div class="md-panel-body">
            <el-form label-position="top">
              <el-form-item label="原密码">
                <el-input v-model="password.oldPassword" type="password" show-password />
              </el-form-item>
              <el-form-item>
                <template #label>新密码<HelpTip :width="300">6-64 位。</HelpTip></template>
                <el-input v-model="password.newPassword" type="password" show-password />
              </el-form-item>
              <el-form-item label="确认新密码">
                <el-input v-model="password.confirm" type="password" show-password />
              </el-form-item>
              <div class="md-form-footer">
                <el-button type="primary" :loading="passwordSaving" @click="savePassword">
                  <MdIcon name="key" :size="14" />修改密码
                </el-button>
              </div>
            </el-form>
          </div>
        </section>

        <section class="md-card-panel">
          <div class="md-panel-head">
            <div class="md-panel-head__text"><h2>关于</h2><p class="md-panel-desc">当前版本与常用入口。</p></div>
          </div>
          <div class="md-panel-body">
            <div class="md-profile__rows">
              <div class="md-profile__row"><span>版本</span><span>MiniDocs 1.2.11</span></div>
              <div class="md-profile__row">
                <span>接口文档</span>
                <!-- swagger 由后端伺服，要带 context-path；页面基址是根，两者不是同一段 -->
                <span><a :href="appBackendHref('/swagger-ui.html')" target="_blank" class="md-text-link">/swagger-ui.html</a></span>
              </div>
              <div class="md-profile__row">
                <span>首页</span>
                <span><a :href="appHref('/')" target="_blank" class="md-text-link">打开首页</a></span>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 验证状态挂在输入框下方而不是右侧：按钮文案长，塞进 520px 栏宽里会和邮箱正文抢行 */
.md-account-email {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  width: 100%;
}
.md-account-email__note {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
</style>
