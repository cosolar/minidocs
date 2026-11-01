<script setup lang="ts">
/**
 * 验证邮件的落地页（路由 {@code /verify-email?token=...}，公开、无顶栏）。
 *
 * <p>这一页的唯一职责是把链接里的 token 交给后端并如实说结果。它是**从零会话状态**打开的：
 * 收件人可能刚注册完、浏览器里没有任何登录态，所以这里不查 {@code /auth/me}、不做跳转预判，
 * 成功之后把人送到登录页由他自己进门。</p>
 *
 * <p>提交是 POST：邮件安全扫描器会预取链接指向的地址，GET 一次等于替用户把链接用掉，
 * 等他真点开时就只剩「链接无效」。</p>
 *
 * <p>token 只在这一次请求里出现，不写进任何本地存储，也不拼进日志或 URL 之外的地方 ——
 * 它是一次性秘密，页面上能说的只有结论。</p>
 */
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { authApi } from '@/user/api'
import { toApiErrorInfo } from '@/shared/api/http'
import Icon from '@/user/components/Icon.vue'

type Phase = 'pending' | 'done' | 'failed'

const route = useRoute()
const phase = ref<Phase>('pending')
const verifiedEmail = ref('')
const failure = ref('')

onMounted(async () => {
  const token = String(route.query.token ?? '').trim()
  if (!token) {
    phase.value = 'failed'
    failure.value = '链接里没带上验证令牌 —— 可能是邮件被截断，或复制时漏掉了后半段。'
    return
  }
  try {
    const result = await authApi.verifyEmail(token)
    verifiedEmail.value = result.email || ''
    phase.value = 'done'
  } catch (error) {
    phase.value = 'failed'
    failure.value = toApiErrorInfo(error).message
  }
})
</script>

<template>
  <div class="md-state">
    <main class="md-lock">
      <div class="md-lock__icon" aria-hidden="true">
        <Icon :name="phase === 'failed' ? 'close' : 'mail'" :size="30" />
      </div>

      <template v-if="phase === 'pending'">
        <h1>正在验证邮箱</h1>
        <p>请稍候，正在核对这条链接。</p>
      </template>

      <template v-else-if="phase === 'done'">
        <h1>邮箱已验证</h1>
        <p>{{ verifiedEmail ? `「${verifiedEmail}」已经归你了。` : '这个邮箱已经归你了。' }}现在可以登录开始写作。</p>
        <div class="md-lock__form">
          <router-link class="md-btn md-btn--primary" to="/login">
            <Icon name="login" :size="14" />去登录
          </router-link>
        </div>
      </template>

      <template v-else>
        <h1>验证没有完成</h1>
        <p>{{ failure }}</p>
        <div class="md-lock__form">
          <router-link class="md-btn md-btn--primary" to="/login">
            <Icon name="login" :size="14" />回登录页重新发送
          </router-link>
        </div>
        <p class="md-verify__foot">链接一次有效、24 小时内可用；过期或重复点开都会失效，重新发送一封即可。</p>
      </template>
    </main>
  </div>
</template>

<style scoped>
.md-verify__foot {
  margin: 16px 0 0;
  color: var(--md-text-3);
  font-size: 12px;
  line-height: 1.6;
}
</style>
