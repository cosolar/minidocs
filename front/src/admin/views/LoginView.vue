<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import MdIcon from '@/admin/components/MdIcon.vue'
import AuthShell from '@/admin/components/AuthShell.vue'
import HumanCheck from '@/admin/components/HumanCheck.vue'
import { authApi } from '@/admin/api'
import { useAuthStore } from '@/admin/stores/auth'
import { appHref } from '@/shared/appBase'
import { toApiErrorInfo } from '@/shared/api/http'

/**
 * 登录页。
 *
 * <p>外壳由 {@link AuthShell} 提供：桌面端「左品牌 / 右表单」，窄屏收成紧凑品牌头。
 * 注册页共用同一个外壳，只有文案与表单字段不同。</p>
 *
 * <p>两种登录方式：默认用户名 + 密码；密码连续输错 3 次后，后端返回
 * {@code 40101}，表单自动切换到「邮箱验证码」，验证通过直接进入平台，
 * 随后由管理端布局提醒重设密码。两种方式共用一个外壳，切换时不跳页面。</p>
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const POINTS = [
  { icon: 'edit', strong: '写作即所得', small: '像写文档一样记录，随时回来修改，手机和电脑都能看' },
  { icon: 'users', strong: '组织与权限', small: '按知识库整理资料，谁能看、谁能改都由你决定' },
  { icon: 'share', strong: '分享免登录', small: '生成链接就能发给朋友，还能设置访问密码和有效期' }
]

/** 当前登录方式：密码优先，输错三次后切到验证码 */
const mode = ref<'password' | 'code'>('password')

const form = reactive({ username: '', password: '' })
const loading = ref(false)

/**
 * 滑块验证码。
 *
 * <p>票据是「这张图已经滑对了」的凭证，登录时带上、后端消费即作废。
 * 所以每次登录失败（密码错、账号停用、票据过期）都要重新滑一次 —— 这不是刁难，
 * 正是「一次验证只能试一次密码」这条防线本身。</p>
 */
const captchaRef = ref<InstanceType<typeof HumanCheck> | null>(null)
const captchaTicket = ref('')

function onCaptchaPassed(ticket: string) {
  captchaTicket.value = ticket
}

/**
 * 邮箱闸门：非空表示这次失败是「邮箱还没验证」，横幅要挂到用户改账号或登录成功为止。
 *
 * <p>后端 40302 的回包带着打码后的地址，够人认出是哪个收件箱，又不足以让这张页面
 * 变成探测别人邮箱的工具。</p>
 */
const mailGate = ref<{ account: string; email: string } | null>(null)
const resending = ref(false)

/** 验证码登录的表单状态 */
const codeForm = reactive({ account: '', code: '' })
const codeSending = ref(false)
const codeSubmitting = ref(false)
/** 发码冷却：后端同一账号 60 秒只发一次，按钮跟着倒计时，别让人靠反复点击去试规则 */
const codeCooldown = ref(0)
let codeTimer: ReturnType<typeof setInterval> | undefined
/** 码发到哪个邮箱（后端打码），让用户确认收件箱没填错 */
const maskedEmail = ref('')
/** 切换过来的原因提示，挂在验证码表单上方 */
const codeNotice = ref('')

function startCooldown(seconds: number) {
  codeCooldown.value = Math.max(0, seconds)
  if (codeTimer) clearInterval(codeTimer)
  if (codeCooldown.value <= 0) return
  codeTimer = setInterval(() => {
    codeCooldown.value -= 1
    if (codeCooldown.value <= 0) {
      clearInterval(codeTimer)
      codeTimer = undefined
    }
  }, 1000)
}

onBeforeUnmount(() => {
  if (codeTimer) clearInterval(codeTimer)
})

/** 切到验证码登录：把已经填过的账号带过去，省得再抄一遍。 */
function switchToCode(notice: string) {
  mode.value = 'code'
  codeForm.account = form.username.trim()
  codeNotice.value = notice
  maskedEmail.value = ''
  // 票据只服务于密码登录，切走后残留一张「已滑好」的图没有意义
  captchaTicket.value = ''
  captchaRef.value?.reset()
}

/** 手动切回密码登录：验证码流程随时可以放弃。 */
function switchToPassword() {
  mode.value = 'password'
  codeNotice.value = ''
  mailGate.value = null
}

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  if (!captchaTicket.value) {
    ElMessage.warning('请先完成人机验证')
    captchaRef.value?.reset()
    return
  }
  mailGate.value = null
  loading.value = true
  try {
    await auth.login(form.username, form.password, captchaTicket.value)
    ElMessage.success('登录成功')
    // 默认落在 /console：组织段不在地址栏里就没法进任何管理页，交给落地页去解析该进哪个组织
    const redirect = (route.query.redirect as string) || '/console'
    router.replace(redirect)
  } catch (error) {
    // 票据已经在上一个请求里被消费掉了，无论这次失败是什么原因，都得换一张图重新点
    captchaTicket.value = ''
    captchaRef.value?.reset()
    // 这条请求带 silentError，全局 onError 不弹 401，所以「用户名或密码错误」必须在这里说出口。
    // 直接吞掉的话，用户点了登录什么反应都没有，只能反复检查自己有没有敲错。
    const info = toApiErrorInfo(error)
    if (info.code === 40101) {
      // 密码已经连续输错三次：继续在同一个框里试只会撞上限流，换成邮箱验证码。
      // 切换原因由表单上方的提示条说明，不再另弹一条 toast 重复一遍。
      switchToCode('密码连续输错 3 次，已为你切换为邮箱验证码登录')
      return
    }
    if (info.code === 40302) {
      // 这一种失败重敲多少遍都一样，给一条横幅和一个重发入口，别混进「用户名或密码错误」
      const payload = info.data as { email?: string } | undefined
      mailGate.value = { account: form.username.trim(), email: payload?.email || '' }
      return
    }
    ElMessage.error(info.message)
  } finally {
    loading.value = false
  }
}

/** 发送登录验证码：收件地址由后端从账号里取，前端只交账号。 */
async function sendCode() {
  const account = codeForm.account.trim()
  if (!account) {
    ElMessage.warning('请先填写用户名或邮箱')
    return
  }
  codeSending.value = true
  try {
    const result = await authApi.sendLoginCode(account)
    maskedEmail.value = result.maskedEmail
    startCooldown(result.cooldownSeconds)
    ElMessage.success(`验证码已发送到 ${result.maskedEmail}，${result.validMinutes} 分钟内有效`)
  } catch (error) {
    const info = toApiErrorInfo(error)
    // 刚发过一封时后端回 42903，把剩余等待时间显式摆在按钮上
    if (info.code === 42903) startCooldown(60)
    ElMessage.error(info.message)
  } finally {
    codeSending.value = false
  }
}

async function submitCode() {
  const account = codeForm.account.trim()
  const code = codeForm.code.trim()
  if (!account) {
    ElMessage.warning('请填写用户名或邮箱')
    return
  }
  if (!/^\d{6}$/.test(code)) {
    ElMessage.warning('请输入邮件里的 6 位数字验证码')
    return
  }
  codeSubmitting.value = true
  try {
    await auth.loginByCode(account, code)
    ElMessage.success('验证通过，已为你登录')
    const redirect = (route.query.redirect as string) || '/console'
    router.replace(redirect)
  } catch (error) {
    ElMessage.error(toApiErrorInfo(error).message)
  } finally {
    codeSubmitting.value = false
  }
}

/** 公开重发：账号取自当前输入框。后端对「查无此人」「早已验证过」回同一句话，这里不解释差别。 */
async function resend() {
  const account = mailGate.value?.account
  if (!account) {
    return
  }
  resending.value = true
  try {
    await authApi.resendVerification(account)
    ElMessage.success('验证邮件已发送。没有收到请检查邮箱是否填写正确，以及邮件是否在垃圾邮件箱里')
  } finally {
    resending.value = false
  }
}
</script>

<template>
  <AuthShell :points="POINTS">
    <template #headline>把知识写下来，<br>把文档送出去</template>
    <template #lead>记录面试经验、整理求职资料、分享给朋友，一个地方完成。</template>

    <header class="md-login__head">
      <h2>{{ mode === 'password' ? '欢迎回来' : '用邮箱验证码登录' }}</h2>
      <p>{{ mode === 'password' ? '登录后进入管理工作台' : '验证码会发到这个账号绑定的邮箱' }}</p>
    </header>

    <!-- 密码登录 -->
    <el-form v-if="mode === 'password'" label-position="top" class="md-login__form" @submit.prevent="submit">
      <el-alert
        v-if="mailGate"
        class="md-login__gate"
        type="warning"
        :closable="false"
        show-icon
        :title="mailGate.email ? `邮箱 ${mailGate.email} 还没验证，登录前请先点一下验证邮件里的链接` : '这个账号的邮箱还没验证，登录前请先点一下验证邮件里的链接'"
      >
        <span class="md-login__gate__action">
          没收到？
          <el-button link type="primary" :loading="resending" @click="resend">重新发送验证邮件</el-button>
        </span>
      </el-alert>
      <el-form-item label="邮箱 / 用户名">
        <el-input v-model="form.username" size="large" placeholder="you@example.com" autocomplete="username">
          <template #prefix><MdIcon name="mail" :size="15" /></template>
        </el-input>
      </el-form-item>
      <el-form-item label="密码">
        <el-input
          v-model="form.password"
          type="password"
          size="large"
          show-password
          placeholder="请输入密码"
          autocomplete="current-password"
          @keyup.enter="submit"
        >
          <template #prefix><MdIcon name="lock" :size="15" /></template>
        </el-input>
      </el-form-item>
      <!-- 滑块放在密码与按钮之间：视觉顺序与操作顺序一致，也避免用户填完就点登录被拦下 -->
      <div class="md-login__captcha">
        <HumanCheck ref="captchaRef" @passed="onCaptchaPassed" />
      </div>
      <el-button type="primary" size="large" class="md-login__submit" :loading="loading" @click="submit">
        <MdIcon name="login" :size="16" />登录
      </el-button>
    </el-form>

    <!-- 邮箱验证码登录 -->
    <el-form v-else label-position="top" class="md-login__form" @submit.prevent="submitCode">
      <el-alert
        v-if="codeNotice"
        class="md-login__gate"
        type="info"
        :closable="false"
        show-icon
        :title="codeNotice"
      />
      <el-form-item label="邮箱 / 用户名">
        <el-input
          v-model="codeForm.account"
          size="large"
          placeholder="you@example.com"
          autocomplete="username"
          @keyup.enter="sendCode"
        >
          <template #prefix><MdIcon name="mail" :size="15" /></template>
        </el-input>
      </el-form-item>
      <el-form-item label="邮箱验证码">
        <div class="md-login__code-row">
          <el-input
            v-model="codeForm.code"
            size="large"
            maxlength="6"
            placeholder="邮件里的 6 位数字"
            autocomplete="one-time-code"
            @keyup.enter="submitCode"
          >
            <template #prefix><MdIcon name="key" :size="15" /></template>
          </el-input>
          <el-button
            size="large"
            class="md-login__code-send"
            :loading="codeSending"
            :disabled="codeCooldown > 0"
            @click="sendCode"
          >
            {{ codeCooldown > 0 ? `${codeCooldown} 秒` : '发送验证码' }}
          </el-button>
        </div>
        <p v-if="maskedEmail" class="md-login__code-hint">验证码已发到 {{ maskedEmail }}，没收到请检查垃圾邮件箱</p>
      </el-form-item>
      <el-button type="primary" size="large" class="md-login__submit" :loading="codeSubmitting" @click="submitCode">
        <MdIcon name="login" :size="16" />验证并登录
      </el-button>
      <el-button link class="md-login__back" @click="switchToPassword">← 返回密码登录</el-button>
    </el-form>

    <div class="md-login__links">
      <span>还没有账号？<router-link to="/register">邮箱注册</router-link></span>
      <a :href="appHref('/')">← 返回首页</a>
    </div>
  </AuthShell>
</template>

<style scoped>
/* el-form-item 自带下外边距，这里只补滑块与按钮之间的那一小段 */
.md-login__captcha {
  margin: 2px 0 18px;
}

/* 验证码输入 + 发送按钮同一行：按钮上的倒计时数字会变，宽度固定住免得整行抖动 */
.md-login__code-row {
  display: flex;
  gap: 10px;
  width: 100%;
}
.md-login__code-send {
  flex: 0 0 124px;
  min-width: 124px;
}
.md-login__code-hint {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--md-text-3);
}
.md-login__back {
  margin-top: 10px;
  align-self: flex-start;
}
</style>
