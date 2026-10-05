<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MdIcon from '@/admin/components/MdIcon.vue'
import { useAuthStore } from '@/admin/stores/auth'
import { useOrgStore } from '@/admin/stores/org'

/**
 * 落地页：把「进哪个组织」这件事一次性问完再跳走。
 *
 * <p>之所以需要它：登录后、或直接打开 {@code /console} 时，地址栏里还没有组织段，
 * 而组织只能从地址栏来（规范 §3.4）。这里读 {@code /api/me} 的 {@code lastTenantSlug}
 * 决定落点，然后把地址改写成 {@code /console/{org}/kbs} —— 用户停不到的中间态。
 * 查询串跟着一起改写，门户上的「新建知识库」这类入口才不会半路丢参数。</p>
 */
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const org = useOrgStore()
const message = ref('正在进入你的工作区…')

onMounted(async () => {
  try {
    await org.load(true)
  } catch {
    message.value = '无法读取组织列表，请重新登录'
    return
  }
  const entry = org.entry
  if (!entry) {
    // 迁移会为每个既有用户建个人组织，走到这里说明数据被手工改过：说清怎么办，不给白屏
    message.value = '这个账号还没有可用组织。请联系平台管理员，或退出后用新邮箱重新注册。'
    return
  }
  await router.replace({ name: 'kbs', params: { org: entry }, query: route.query })
})

async function logout() {
  await auth.logout()
  org.reset()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="md-entry">
    <p>{{ message }}</p>
    <el-button v-if="!org.tenants.length" text @click="logout">
      <MdIcon name="logout" :size="14" />退出登录
    </el-button>
  </div>
</template>

<style scoped>
.md-entry {
  min-height: 60vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: var(--md-text-2);
  font-size: 14px;
}
</style>
