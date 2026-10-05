<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import ReadBody from '@/user/components/ReadBody.vue'
import ReadStatusBar from '@/user/components/ReadStatusBar.vue'
import LockView from '@/user/views/LockView.vue'
import StatusView from '@/user/views/StatusView.vue'
import { portalApi } from '@/user/api'
import { toApiErrorInfo } from '@/shared/api/http'
import { useBodyClasses } from '@/user/composables/useBodyClasses'
import type { ReadView } from '@/user/api/types'

type State = 'loading' | 'password' | 'ok' | 'notfound'

const route = useRoute()

const state = ref<State>('loading')
/** 已有正文、正在切换文档：三栏骨架留在原地，只在正文区做轻量提示 */
const switching = ref(false)
const view = ref<ReadView | null>(null)
/** 私有库口令弹层要用的库名（此时正文尚未下发） */
const kbName = ref('')
const errorMessage = ref('')
const verifyError = ref('')
const submitting = ref(false)
const lockRef = ref<InstanceType<typeof LockView> | null>(null)

const orgSlug = computed(() => String(route.params.orgSlug || ''))
const slug = computed(() => String(route.params.slug || ''))
const docPath = computed(() => (typeof route.query.path === 'string' ? route.query.path : undefined))

// 阅读态走阅读页样式，口令 / 未找到态居中展示
useBodyClasses(
  computed(() => (state.value === 'ok' || state.value === 'loading' ? ['md-page--read'] : ['md-page--error']))
)

watch(
  () => [orgSlug.value, slug.value, docPath.value],
  () => void load(),
  { immediate: true }
)

async function load() {
  if (!orgSlug.value || !slug.value) {
    state.value = 'notfound'
    return
  }
  /*
   * 已经有正文时不退回整页骨架：切换文档只该换正文与大纲，左侧目录、右侧大纲、底部状态栏
   * 都留在原地。否则整屏闪一下「加载中…」，观感等同于整页刷新，目录的展开状态也会被重置。
   */
  if (state.value === 'ok' && view.value !== null) {
    switching.value = true
  } else {
    state.value = 'loading'
  }
  verifyError.value = ''
  try {
    const data = await portalApi.kb(orgSlug.value, slug.value, docPath.value)
    kbName.value = data.kbName || ''
    if (data.state === 'password') {
      // 分享已加密：正文不下发，先过口令门
      state.value = 'password'
      await nextTick()
      lockRef.value?.reset()
      return
    }
    view.value = data.view ?? null
    state.value = 'ok'
  } catch (error) {
    const info = toApiErrorInfo(error)
    // 未发布 / 不在可见集就是 404：读轴不确认库的存在（规范 §2.5）
    errorMessage.value = info.message || '知识库不存在、未公开，或你没有访问权限。'
    state.value = 'notfound'
  } finally {
    switching.value = false
  }
}

async function verify(password: string) {
  submitting.value = true
  verifyError.value = ''
  try {
    await portalApi.verifyKb(orgSlug.value, slug.value, password)
    // 口令通过后后端已下发免密 Cookie，重取一次即可拿到正文
    await load()
  } catch (error) {
    verifyError.value = toApiErrorInfo(error).message || '密码错误，请重试'
    lockRef.value?.reset()
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <p v-if="state === 'loading'" class="md-empty-hint" style="padding-top: 80px;">加载中…</p>

  <LockView
    v-else-if="state === 'password'"
    ref="lockRef"
    :kb-name="kbName"
    :error="verifyError"
    :submitting="submitting"
    @submit="verify"
  />

  <StatusView
    v-else-if="state === 'notfound' || !view"
    code="404"
    title="无法访问该知识库"
    :message="errorMessage || '知识库不存在、未公开，或你没有访问权限。'"
  />

  <template v-else>
    <!--
      阅读页骨架：三栏阅读区吃掉剩余高度、自己内部滚动，底部是常驻状态栏。
      页面级滚动条因此不再出现，状态栏也就不会被长正文推走。
    -->
    <div class="md-read">
      <ReadBody :view="view" :switching="switching" />
      <ReadStatusBar
        :word-count="view.wordCount"
        :line-count="view.lineCount"
        :reading-minutes="view.readingMinutes"
        :published-text="view.publishedText"
        :updated-text="view.updatedText"
      />
    </div>
  </template>
</template>
