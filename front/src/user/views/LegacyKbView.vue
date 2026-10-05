<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StatusView from '@/user/views/StatusView.vue'
import Icon from '@/user/components/Icon.vue'
import { portalApi } from '@/user/api'
import { portalKbUrl } from '@/shared/portal'
import { useBodyClasses } from '@/user/composables/useBodyClasses'
import type { KbVO } from '@/shared/api/types'

/**
 * v1 老链接 /kb/{slug} 的去向（规范 §7.1.3）。
 *
 * <p>唯一命中由后端 301 处理，走到这里说明命中零个或多个：多个时列出可选知识库，零个时给未找到态。
 * 列表来自后端的可见集判定，因此不会把他人库的存在性暴露给没有读权限的人。</p>
 */
const route = useRoute()
const router = useRouter()

const slug = computed(() => String(route.params.slug || ''))
const matches = ref<KbVO[]>([])
const loading = ref(true)

useBodyClasses(computed(() => ['md-page--error']))

watch(slug, () => void resolve(), { immediate: true })

/** 个人组织的 slug 本身就是 u-{用户名}，团队组织直接显示 slug。 */
function orgLabel(kb: KbVO) {
  return kb.tenantSlug?.startsWith('u-') ? kb.ownerName || kb.tenantSlug : kb.tenantSlug
}

async function resolve() {
  loading.value = true
  try {
    const list = await portalApi.locate(slug.value)
    const only = list.length === 1 ? list[0] : undefined
    if (only?.tenantSlug) {
      // 后端 301 之外兜一手：唯一命中仍然直接跳走，不让人多点一次
      await router.replace(portalKbUrl(only.tenantSlug, only.slug, pathQuery()))
      return
    }
    matches.value = list
  } catch {
    matches.value = []
  } finally {
    loading.value = false
  }
}

function pathQuery() {
  return typeof route.query.path === 'string' ? route.query.path : undefined
}
</script>

<template>
  <p v-if="loading" class="md-empty-hint" style="padding-top: 80px;">加载中…</p>
  <StatusView
    v-else-if="!matches.length"
    code="404"
    title="知识库不存在"
    :message="`没有名为 ${slug} 的知识库，或你没有访问权限。`"
  />
  <main v-else class="md-error">
    <h1>找到多个名为「{{ slug }}」的知识库</h1>
    <p>这个老链接没有带组织信息，请选择要访问的知识库。</p>
    <div class="md-locate-list">
      <router-link
        v-for="item in matches"
        :key="item.id"
        class="md-btn md-btn--primary"
        :to="portalKbUrl(item.tenantSlug, item.slug, pathQuery())"
      >
        <Icon name="books" :size="14" />{{ item.name }}<span class="md-locate-list__org">{{ orgLabel(item) }}</span>
      </router-link>
    </div>
  </main>
</template>

<style scoped>
.md-locate-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  justify-content: center;
  margin-top: 24px;
}

.md-locate-list__org {
  margin-left: 8px;
  opacity: .6;
  font-size: 12px;
}
</style>
