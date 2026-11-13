<script setup lang="ts">
/**
 * 账号设置：资料、密码、外观偏好。
 *
 * <p>它刻意<b>不带组织段</b>（{@code /account} 而不是 {@code /console/{org}/settings}）：这三样东西都属于
 * 人，不属于组织，放在组织下面会让人误以为「换个组织设置不同」，而且从阅读页顶栏点进来时
 * 并没有一个自然的 org 可填。组织自己的资料与成员治理在 {@code OrgSettingsView}。</p>
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authApi, avatarApi, tokenApi } from '@/admin/api'
import AdminPageHeader from '@/admin/components/AdminPageHeader.vue'
import MdIcon from '@/admin/components/MdIcon.vue'
import { useIsMobile } from '@/admin/composables/useIsMobile'
import HelpTip from '@/shared/components/HelpTip.vue'
import { appBackendHref, appHref } from '@/shared/appBase'
import { toApiErrorInfo } from '@/shared/api/http'
import { useAuthStore } from '@/admin/stores/auth'
import { useSettingsStore } from '@/shared/stores/settings'
import type { ApiTokenVO } from '@/shared/api/types'

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

/* ------------------------------------------------- 第三方应用授权（访问令牌） */
/**
 * 访问令牌：给第三方应用签发的一枚长期凭证。
 *
 * <p>它换来的身份就是本人，所以第三方能读到的数据与本人登录后读到的一致；
 * 差别在于令牌可以被随时吊销，也可以只给「读」的权限。</p>
 */
const tokens = ref<ApiTokenVO[]>([])
const tokenLoading = ref(false)
const tokenDialog = ref(false)
const tokenCreating = ref(false)
const tokenForm = reactive({ name: '', writeable: false, expiresInDays: 0 })
/** 刚签发出来的令牌原文：只在这个弹窗里出现一次，关闭即消失 */
const issuedToken = ref('')
const issuedDialog = ref(false)
/** 窄屏用卡片而不是表格：七列挤在 390px 里会把「谁是哪枚令牌」挤没了 */
const isMobile = useIsMobile()

const expiryOptions = [
  { label: '长期有效', value: 0 },
  { label: '30 天', value: 30 },
  { label: '90 天', value: 90 },
  { label: '一年', value: 365 }
]

async function loadTokens() {
  tokenLoading.value = true
  try {
    tokens.value = await tokenApi.list()
  } finally {
    tokenLoading.value = false
  }
}

function openTokenDialog() {
  tokenForm.name = ''
  tokenForm.writeable = false
  tokenForm.expiresInDays = 0
  tokenDialog.value = true
}

async function createToken() {
  const name = tokenForm.name.trim()
  if (!name) {
    ElMessage.warning('请填写应用名称，方便日后认出这枚令牌')
    return
  }
  tokenCreating.value = true
  try {
    const created = await tokenApi.create({
      name,
      scopes: tokenForm.writeable ? 'read,write' : 'read',
      expiresInDays: tokenForm.expiresInDays || undefined
    })
    tokenDialog.value = false
    issuedToken.value = created.token
    issuedDialog.value = true
    await loadTokens()
  } finally {
    tokenCreating.value = false
  }
}

async function revokeToken(row: ApiTokenVO) {
  await ElMessageBox.confirm(
    `吊销后「${row.name}」将立即失去访问权限，且无法恢复。`,
    '确认吊销这枚令牌？',
    { confirmButtonText: '吊销', cancelButtonText: '取消', type: 'warning' }
  )
  await tokenApi.revoke(row.id)
  ElMessage.success('令牌已吊销')
  await loadTokens()
}

async function copyIssuedToken() {
  try {
    await navigator.clipboard.writeText(issuedToken.value)
    ElMessage.success('令牌已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选中文本复制')
  }
}

function tokenStatusText(status: string) {
  if (status === 'active') return '生效中'
  if (status === 'expired') return '已过期'
  return '已吊销'
}

function tokenStatusTone(status: string) {
  if (status === 'active') return 'ok'
  if (status === 'expired') return 'warn'
  return 'muted'
}

function tokenTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

onMounted(async () => {
  settings.applyToDom()
  if (!auth.user) await auth.loadProfile()
  account.username = auth.user?.username || ''
  account.displayName = auth.user?.displayName || ''
  account.email = auth.user?.email || ''
  await settings.load()
  await loadTokens()
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
              <div class="md-profile__row"><span>版本</span><span>MiniDocs 1.2.17</span></div>
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

    <section class="md-card-panel md-table-card">
      <div class="md-panel-head">
        <div class="md-panel-head__text">
          <h2>第三方应用授权</h2>
          <p class="md-panel-desc">给第三方应用签发一枚访问令牌，它就能以你的身份读取官方接口的数据。令牌只显示一次，请立刻保存。</p>
        </div>
        <span class="md-spacer" />
        <HelpTip text="令牌等同于你的账号权限：只读令牌只能查询数据，读写令牌还能新建与修改。随时可以在这里吊销。" />
        <el-button type="primary" @click="openTokenDialog">
          <MdIcon name="plus" :size="14" />新建令牌
        </el-button>
      </div>
      <div class="md-panel-body">
        <div v-if="!tokens.length && tokenLoading" class="md-empty">
          <div class="md-empty__title">正在读取令牌…</div>
        </div>
        <div v-else-if="!tokens.length" class="md-empty">
          <span class="md-empty__icon"><MdIcon name="key" :size="22" /></span>
          <div class="md-empty__title">还没有访问令牌</div>
          <p class="md-empty__desc">第三方应用需要凭一枚令牌访问官方接口。签发一枚，把令牌填进它的配置里即可。</p>
          <el-button type="primary" @click="openTokenDialog">
            <MdIcon name="plus" :size="14" />新建令牌
          </el-button>
        </div>
        <template v-else>
          <!-- 窄屏：一枚令牌一张卡，跟「用户管理」同一套读法 -->
          <div v-if="isMobile" class="md-token-cards">
            <div v-for="row in tokens" :key="row.id" class="md-token-card">
              <div class="md-token-card__head">
                <strong>{{ row.name }}</strong>
                <span class="md-tbl-pill" :class="row.writeable ? 'md-tbl-pill--brand' : 'md-tbl-pill--muted'">
                  {{ row.writeable ? '读写' : '只读' }}
                </span>
                <span class="md-tbl-pill" :class="`md-tbl-pill--${tokenStatusTone(row.status)}`">
                  {{ tokenStatusText(row.status) }}
                </span>
              </div>
              <div class="md-token-card__meta">
                <code>{{ row.tokenPrefix }}…</code>
                <span>创建于 {{ tokenTime(row.createdAt) }}</span>
                <span>{{ row.lastUsedAt ? `最近使用 ${tokenTime(row.lastUsedAt)}` : '还没用过' }}</span>
                <span>{{ row.expiresAt ? `有效期至 ${tokenTime(row.expiresAt)}` : '长期有效' }}</span>
              </div>
              <div class="md-token-card__actions">
                <el-button v-if="row.status !== 'revoked'" size="small" type="danger" plain @click="revokeToken(row)">
                  <MdIcon name="trash" :size="14" />吊销
                </el-button>
                <span v-else class="md-token-cell__muted">已作废</span>
              </div>
            </div>
          </div>
          <el-table v-else :data="tokens" v-loading="tokenLoading" style="width: 100%">
          <el-table-column label="应用" min-width="200">
            <template #default="{ row }">
              <div class="md-token-cell">
                <strong>{{ row.name }}</strong>
                <code>{{ row.tokenPrefix }}…</code>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="权限" width="96" align="center">
            <template #default="{ row }">
              <span class="md-tbl-pill" :class="row.writeable ? 'md-tbl-pill--brand' : 'md-tbl-pill--muted'">
                {{ row.writeable ? '读写' : '只读' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96" align="center">
            <template #default="{ row }">
              <span class="md-tbl-pill" :class="`md-tbl-pill--${tokenStatusTone(row.status)}`">
                {{ tokenStatusText(row.status) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="148">
            <template #default="{ row }">{{ tokenTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="最近使用" width="148">
            <template #default="{ row }">{{ row.lastUsedAt ? tokenTime(row.lastUsedAt) : '还没用过' }}</template>
          </el-table-column>
          <el-table-column label="有效期" width="148">
            <template #default="{ row }">{{ row.expiresAt ? tokenTime(row.expiresAt) : '长期有效' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="104" align="right" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status !== 'revoked'" size="small" type="danger" plain @click="revokeToken(row)">
                <MdIcon name="trash" :size="14" />吊销
              </el-button>
              <span v-else class="md-token-cell__muted">已作废</span>
            </template>
          </el-table-column>
        </el-table>
        </template>
      </div>
    </section>

    <el-dialog v-model="tokenDialog" title="新建访问令牌" width="460px">
      <el-form label-position="top">
        <el-form-item label="应用名称">
          <el-input v-model="tokenForm.name" maxlength="64" placeholder="例如：我的数据看板" />
        </el-form-item>
        <el-form-item>
          <template #label>权限<HelpTip :width="320">只读：只能查询数据。读写：还能新建与修改内容。默认给只读。</HelpTip></template>
          <el-radio-group v-model="tokenForm.writeable">
            <el-radio-button :value="false">只读</el-radio-button>
            <el-radio-button :value="true">读写</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="有效期">
          <el-select v-model="tokenForm.expiresInDays" style="width: 100%">
            <el-option v-for="opt in expiryOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tokenDialog = false">取消</el-button>
        <el-button type="primary" :loading="tokenCreating" @click="createToken">签发令牌</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="issuedDialog" title="令牌已生成" width="560px" :close-on-click-modal="false">
      <p class="md-panel-desc">这枚令牌只显示这一次，关闭后无法再次查看。请立刻复制并保存到第三方应用的配置里。</p>
      <div class="md-token-secret">
        <code>{{ issuedToken }}</code>
        <el-button size="small" @click="copyIssuedToken">
          <MdIcon name="copy" :size="14" />复制
        </el-button>
      </div>
      <p class="md-panel-desc md-token-usage">
        调用官方接口时把它放进请求头：<code>Authorization: Bearer &lt;令牌&gt;</code>
      </p>
      <template #footer>
        <el-button type="primary" @click="issuedDialog = false">我已保存</el-button>
      </template>
    </el-dialog>
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

/* 窄屏一枚令牌一张卡：表格七列挤在 390px 里，「谁是哪枚令牌」会被固定列挤没 */
.md-token-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.md-token-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid var(--md-border);
  border-radius: var(--md-radius-sm, 10px);
}
.md-token-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.md-token-card__meta {
  display: flex;
  flex-direction: column;
  gap: 3px;
  color: var(--md-text-3);
  font-size: 12px;
}
.md-token-card__meta code {
  font-family: var(--md-mono, ui-monospace, Menlo, Consolas, monospace);
  color: var(--md-text-2);
}
.md-token-card__actions {
  display: flex;
  justify-content: flex-end;
}

/* 令牌那列：应用名与前缀分两行，前缀是等宽字体 —— 它是给人认「哪一枚」用的 */
.md-token-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.md-token-cell code {
  font-family: var(--md-mono, ui-monospace, Menlo, Consolas, monospace);
  font-size: 11.5px;
  color: var(--md-text-3);
}
.md-token-cell__muted {
  color: var(--md-text-3);
  font-size: 12.5px;
}

/* 刚签发出来的令牌原文：等宽 + 允许换行，别让它把弹窗撑破 */
.md-token-secret {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 14px 0;
  padding: 12px 14px;
  border: 1px solid var(--md-border);
  border-radius: var(--md-radius-sm, 10px);
  background: var(--md-surface-2);
}
.md-token-secret code {
  flex: 1;
  word-break: break-all;
  font-family: var(--md-mono, ui-monospace, Menlo, Consolas, monospace);
  font-size: 12.5px;
  color: var(--md-text);
}
.md-token-usage code {
  font-family: var(--md-mono, ui-monospace, Menlo, Consolas, monospace);
  font-size: 12px;
  color: var(--md-text-2);
}
</style>
