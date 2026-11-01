<script setup lang="ts">
/**
 * 自助注册（路由 {@code /register}，规范 §3.1 / F1）。
 *
 * <p>页面以邮箱为第一身份：用户名会从邮箱本地部分自动填好，用户只在重名时改一下。
 * 注册成功只意味着账号存在了 —— 后端**不回 token**，所以这里紧接着用同一组凭据登录一次。</p>
 *
 * <p>站点开启邮箱验证时，注册前先获取 6 位验证码并填回本页；后端校验通过才建号，
 * 建出来的账号直接是「已验证」，注册完即可登录。之后在账号设置里改邮箱，
 * 仍然走邮件里的验证链接。</p>
 */
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/admin/api'
import { toApiErrorInfo } from '@/shared/api/http'
import { appHref } from '@/shared/appBase'
import MdIcon from '@/admin/components/MdIcon.vue'
import AuthShell from '@/admin/components/AuthShell.vue'
import { useAuthStore } from '@/admin/stores/auth'
import { useSiteStore } from '@/shared/stores/site'

/** 与后端 {@code EmailUtil.SHAPE} 同一套形状判断：只问像不像一个地址，不问收不收得到。 */
const EMAIL_SHAPE = /^[^@\s]+@[^@\s.]+\.[^@\s]+$/

/** 重发倒计时：后端对同一邮箱是 60 秒一次，界面提前把按钮按住，省一次必然失败的往返。 */
const RESEND_COOLDOWN = 60

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const site = useSiteStore()

const POINTS = [
  { icon: 'mail', strong: '邮箱即身份', small: '验证码发到邮箱，用户名也从邮箱自动生成' },
  { icon: 'users', strong: '个人组织', small: '注册即建，只有你一个人；需要协作再邀请成员' },
  { icon: 'books', strong: '多知识库', small: '按主题分库，库级可见性与分享各自独立' }
]

const form = reactive({ username: '', displayName: '', email: '', password: '', confirm: '', code: '' })
const loading = ref(false)
const sendingCode = ref(false)
/** 用户手动改过用户名之后，就不再跟着邮箱覆盖 */
const usernameTouched = ref(false)

/** 站点当前是否要求注册先验证邮箱（后端已把「开关 + SMTP 配齐」与好再回吐）。 */
const verifyRequired = computed(() => site.value.emailVerifyRequired === true)

/** 发码倒计时；与后端「同一邮箱 60 秒一次」的配额对齐。 */
const cooldown = ref(0)
let cooldownTimer: ReturnType<typeof setInterval> | null = null

const emailError = computed(() => {
  const value = form.email.trim()
  if (!value) {
    // 一打开就报红太吵：等用户已经动过表单（哪怕动的是别的字段）再提示必填
    const started = Boolean(form.username.trim() || form.password || form.confirm || form.code)
    return verifyRequired.value && started ? '注册需要邮箱验证码，请填写' : ''
  }
  return EMAIL_SHAPE.test(value) ? '' : '邮箱格式不正确，例如 alice@example.com'
})

/** 邮箱一变就把用户名跟着改成它的本地部分；用户动过用户名就不再插手 */
watch(() => form.email, (value) => {
  // 换邮箱后旧验证码不再对应当前地址：清掉输入、放开倒计时，允许立刻给新地址发码
  form.code = ''
  stopCooldown()
  if (usernameTouched.value) {
    return
  }
  const local = value.split('@')[0] ?? ''
  const derived = local.toLowerCase()
    .replace(/[^a-z0-9_]/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 32)
  form.username = derived.length >= 3 ? derived : ''
})

function stopCooldown() {
  if (cooldownTimer) {
    clearInterval(cooldownTimer)
    cooldownTimer = null
  }
  cooldown.value = 0
}

function startCooldown(seconds = RESEND_COOLDOWN) {
  cooldown.value = seconds > 0 ? seconds : RESEND_COOLDOWN
  if (cooldownTimer) {
    clearInterval(cooldownTimer)
  }
  cooldownTimer = setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0 && cooldownTimer) {
      clearInterval(cooldownTimer)
      cooldownTimer = null
    }
  }, 1000)
}

/** 只留数字：验证码是 6 位纯数字，粘贴带空格/短横线时也顺手清掉。 */
function sanitizeCode(value: string) {
  form.code = value.replace(/\D/g, '').slice(0, 6)
}

/** 获取验证码：失败原因（SMTP 没配好、邮箱已占用、发得太频繁）直接弹出来。 */
async function sendCode() {
  if (sendingCode.value || cooldown.value > 0) {
    return
  }
  const email = form.email.trim()
  if (!email) {
    ElMessage.warning('请先填写邮箱')
    return
  }
  if (!EMAIL_SHAPE.test(email)) {
    ElMessage.warning('邮箱格式不正确，例如 alice@example.com')
    return
  }
  sendingCode.value = true
  try {
    const issued = await authApi.sendRegisterCode(email)
    ElMessage.success(`验证码已发往 ${email}，${issued.validMinutes} 分钟内有效`)
    startCooldown(issued.cooldownSeconds)
  } catch (error) {
    ElMessage.error(toApiErrorInfo(error).message)
  } finally {
    sendingCode.value = false
  }
}

onBeforeUnmount(() => {
  if (cooldownTimer) {
    clearInterval(cooldownTimer)
    cooldownTimer = null
  }
})

async function submit() {
  const username = form.username.trim()
  if (!/^[A-Za-z0-9_]{3,32}$/.test(username)) {
    ElMessage.warning('用户名需为 3-32 位字母、数字或下划线')
    return
  }
  const email = form.email.trim()
  if (!email) {
    ElMessage.warning('请填写邮箱')
    return
  }
  if (!EMAIL_SHAPE.test(email)) {
    ElMessage.warning('邮箱格式不正确，例如 alice@example.com')
    return
  }
  const emailCode = form.code.trim()
  if (verifyRequired.value && !/^\d{6}$/.test(emailCode)) {
    ElMessage.warning('请填写 6 位邮箱验证码；没收到可以点「获取验证码」重发')
    return
  }
  if (form.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  if (form.password !== form.confirm) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    await authApi.register({
      username,
      password: form.password,
      displayName: form.displayName.trim() || undefined,
      email,
      emailCode: verifyRequired.value ? emailCode : undefined
    })
    // 注册接口不给会话，紧接着登录一次；失败也不把账号回滚，提示去登录即可
    try {
      await auth.login(username, form.password)
      ElMessage.success('注册完成，个人组织已经建好')
      router.replace((route.query.redirect as string) || '/console')
      return
    } catch {
      ElMessage.success('注册完成，请登录')
    }
  } catch (error) {
    // register 带 silentError，全局 onError 不再兜底，所以「用户名已被占用」「自助注册已关闭」
    // 这些文案必须在这里说出口 —— 否则用户点完注册什么反应都没有，只能反复试。
    ElMessage.error(toApiErrorInfo(error).message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthShell :points="POINTS">
    <template #headline>从第一篇文档开始，<br>建立你的知识库</template>
    <template #lead>填写邮箱即可开始；需要协作时再建组织、邀请成员，第一篇文档现在就能写。</template>

    <header class="md-login__head">
        <h2>创建账号</h2>
        <p>{{ verifyRequired ? '先获取邮箱验证码，填回来就能完成注册' : '注册后会自动进入你的第一个知识库' }}</p>
      </header>

      <el-form label-position="top" class="md-login__form" @submit.prevent="submit">
        <el-form-item :error="emailError" label="邮箱">
          <el-input v-model="form.email" size="large" maxlength="128" placeholder="you@example.com" autocomplete="email">
            <template #prefix><MdIcon name="mail" :size="15" /></template>
          </el-input>
        </el-form-item>

        <el-form-item v-if="verifyRequired" label="邮箱验证码">
          <div class="md-login__code">
            <el-input
              v-model="form.code"
              size="large"
              maxlength="6"
              inputmode="numeric"
              autocomplete="one-time-code"
              placeholder="6 位数字"
              @input="sanitizeCode"
            >
              <template #prefix><MdIcon name="lock" :size="15" /></template>
            </el-input>
            <el-button class="md-login__code-btn" :loading="sendingCode" :disabled="cooldown > 0" @click="sendCode">
              {{ cooldown > 0 ? `${cooldown}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <div class="md-login__row">
          <el-form-item label="用户名">
            <el-input
              v-model="form.username"
              size="large"
              placeholder="自动取自邮箱"
              autocomplete="username"
              maxlength="32"
              @input="usernameTouched = true"
            >
              <template #prefix><MdIcon name="user" :size="15" /></template>
            </el-input>
          </el-form-item>
          <el-form-item label="昵称（选填）">
            <el-input v-model="form.displayName" size="large" maxlength="64" placeholder="怎么称呼你">
              <template #prefix><MdIcon name="user" :size="15" /></template>
            </el-input>
          </el-form-item>
        </div>

        <div class="md-login__row">
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              show-password
              autocomplete="new-password"
              placeholder="至少 6 位"
            >
              <template #prefix><MdIcon name="lock" :size="15" /></template>
            </el-input>
          </el-form-item>
          <el-form-item label="确认密码">
            <el-input
              v-model="form.confirm"
              type="password"
              size="large"
              show-password
              autocomplete="new-password"
              placeholder="再输一次"
              @keyup.enter="submit"
            >
              <template #prefix><MdIcon name="lock" :size="15" /></template>
            </el-input>
          </el-form-item>
        </div>

        <el-button type="primary" size="large" class="md-login__submit" :loading="loading" @click="submit">
          <MdIcon name="user-plus" :size="16" />创建账号
        </el-button>
      </el-form>

      <div class="md-login__links">
        <span>已有账号？<router-link to="/login">去登录</router-link></span>
        <a :href="appHref('/')">← 返回门户首页</a>
      </div>
  </AuthShell>
</template>

<style scoped>
/* 验证码一行：输入框吃满剩余宽度，按钮固定宽度并与输入框同高 */
.md-login__code {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  width: 100%;
}
.md-login__code .el-input { width: 100%; }
.md-login__code-btn {
  height: 46px;
  padding: 0 16px;
  border-radius: 12px;
  font-size: 13px;
  font-weight: 600;
}

/*
 * 窄屏把「输入框 + 获取验证码」改成上下两行：
 * 并排时按钮的 min-content 宽度会把整列撑出视口，手机上表现为右侧被切掉。
 */
@media (max-width: 560px) {
  .md-login__code {
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
  }
  .md-login__code-btn { width: 100%; }
}
</style>
