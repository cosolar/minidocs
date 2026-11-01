<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import MdIcon from '@/admin/components/MdIcon.vue'
import AuthShell from '@/admin/components/AuthShell.vue'
import { authApi } from '@/admin/api'
import { useAuthStore } from '@/admin/stores/auth'
import { appHref } from '@/shared/appBase'
import { toApiErrorInfo } from '@/shared/api/http'

/**
 * 登录页。
 *
 * <p>外壳由 {@link AuthShell} 提供：桌面端「左品牌 / 右表单」，窄屏收成紧凑品牌头。
 * 注册页共用同一个外壳，只有文案与表单字段不同。</p>
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const POINTS = [
  { icon: 'edit', strong: '写作即所得', small: 'Markdown 编辑与阅读两套渲染，表格、代码、公式、图表都齐' },
  { icon: 'users', strong: '组织与权限', small: '成员角色、库级可见性、操作审计，各管一段' },
  { icon: 'share', strong: '分享免登录', small: '外链即读，可设口令与有效期，浏览量可见' }
]

const form = reactive({ username: '', password: '' })
const loading = ref(false)

/**
 * 邮箱闸门：非空表示这次失败是「邮箱还没验证」，横幅要挂到用户改账号或登录成功为止。
 *
 * <p>后端 40302 的回包带着打码后的地址，够人认出是哪个收件箱，又不足以让这张页面
 * 变成探测别人邮箱的工具。</p>
 */
const mailGate = ref<{ account: string; email: string } | null>(null)
const resending = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  mailGate.value = null
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    // 默认落在 /console：组织段不在地址栏里就没法进任何管理页，交给落地页去解析该进哪个组织
    const redirect = (route.query.redirect as string) || '/console'
    router.replace(redirect)
  } catch (error) {
    // 这条请求带 silentError，全局 onError 不弹 401，所以「用户名或密码错误」必须在这里说出口。
    // 直接吞掉的话，用户点了登录什么反应都没有，只能反复检查自己有没有敲错。
    const info = toApiErrorInfo(error)
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
    <template #lead>Markdown 写作、实时预览、组织与权限、对外分享，一套系统走完。</template>

    <header class="md-login__head">
      <h2>欢迎回来</h2>
      <p>登录后进入管理工作台</p>
    </header>

    <el-form label-position="top" class="md-login__form" @submit.prevent="submit">
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
      <el-button type="primary" size="large" class="md-login__submit" :loading="loading" @click="submit">
        <MdIcon name="login" :size="16" />登录
      </el-button>
    </el-form>

    <div class="md-login__links">
      <span>还没有账号？<router-link to="/register">邮箱注册</router-link></span>
      <a :href="appHref('/')">← 返回门户首页</a>
    </div>
  </AuthShell>
</template>
