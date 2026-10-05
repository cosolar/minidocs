<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { searchApi } from '@/user/api'
import { useSearchPanel } from '@/user/composables/useSearchPanel'
import type { SearchHit } from '@/shared/api/types'

/**
 * 面板里的一行。L1 联想与 L2 正文命中形状不同（后者多一段 snippet），
 * 归成一份行模型，模板就只有一种渲染方式，分组下标也能继续用同一套键盘导航。
 */
interface Row {
  type: string
  title: string
  subtitle?: string
  url: string
  snippet?: string
}

const { visible, keyword, open, close } = useSearchPanel()

const inputEl = ref<HTMLInputElement | null>(null)
/** 两路结果分开存：联想先回（200ms），正文扫描晚一拍（500ms），谁回来都不覆盖对方那一组 */
const nameRows = ref<Row[]>([])
const bodyRows = ref<Row[]>([])
const bodyTotal = ref(0)
const active = ref(-1)
const loading = ref(false)
/** 正文那一组单独等：联想已经回来了，就不该整页转圈，但也不能让人以为只有这几条 */
const bodyPending = ref(false)

/** 每次输入一个序号，晚到的旧响应丢掉：否则慢的那次会覆盖快的那次的结果 */
let seq = 0
let suggestTimer: number | undefined
let bodyTimer: number | undefined

const hasKeyword = computed(() => keyword.value.trim().length > 0)
/** 分组顺序就是这里拼行的顺序，键盘上下走的是同一份 rows */
const rows = computed(() => [...nameRows.value, ...bodyRows.value])

const groups = computed(() => {
  const order: Array<[string, string]> = [['kb', '知识库'], ['doc', '文档'], ['tag', '标签'], ['body', '正文匹配']]
  const buckets: Array<{ title: string; list: Row[]; offset: number }> = []
  let offset = 0
  for (const [type, title] of order) {
    const list = rows.value.filter((item) => item.type === type)
    if (list.length) {
      buckets.push({ title, list, offset })
      offset += list.length
    }
  }
  return buckets
})

watch(visible, async (isOpen) => {
  if (isOpen) {
    document.body.style.overflow = 'hidden'
    await nextTick()
    inputEl.value?.focus()
    inputEl.value?.select()
  } else {
    document.body.style.overflow = ''
  }
})

/** 结果还配不配当前这次输入：既看序号，也看关键字（面板可能在别处被改过） */
function isCurrent(text: string, id: number) {
  return id === seq && keyword.value.trim() === text
}

function onInput() {
  const text = keyword.value.trim()
  window.clearTimeout(suggestTimer)
  window.clearTimeout(bodyTimer)
  seq++
  if (!text) {
    nameRows.value = []
    bodyRows.value = []
    bodyTotal.value = 0
    bodyPending.value = false
    active.value = -1
    loading.value = false
    return
  }
  loading.value = true
  bodyPending.value = true
  /* 正文检索是服务端的有界扫描（最多读两千篇文件），所以比联想晚一拍：
     连续输入时绝大多数中间态只该花在一次目录扫描上。 */
  suggestTimer = window.setTimeout(() => void loadSuggest(text), 200)
  bodyTimer = window.setTimeout(() => void loadBody(text), 500)
}

async function loadSuggest(text: string) {
  const id = ++seq
  try {
    const data = await searchApi.suggest(text)
    if (!isCurrent(text, id)) return
    nameRows.value = [...(data.kbs || []), ...(data.docs || []), ...(data.tags || [])]
  } catch {
    if (isCurrent(text, id)) nameRows.value = []
  } finally {
    if (id === seq) loading.value = false
    clampActive()
  }
}

async function loadBody(text: string) {
  const id = seq
  try {
    const result = await searchApi.search({ q: text, page: 1, size: 8 })
    if (!isCurrent(text, id)) return
    bodyTotal.value = result.total
    bodyRows.value = result.list.map((hit: SearchHit) => ({
      type: 'body',
      title: hit.title,
      subtitle: hit.kbName,
      url: hit.url,
      snippet: hit.snippet
    }))
  } catch {
    if (isCurrent(text, id)) {
      bodyTotal.value = 0
      bodyRows.value = []
    }
  } finally {
    if (id === seq) bodyPending.value = false
    clampActive()
  }
}

/** 正文那一组是后到的，行数变了要把手电筒下标收进范围内，别让高亮停在空行上 */
function clampActive() {
  const count = rows.value.length
  active.value = count ? Math.min(Math.max(active.value, 0), count - 1) : -1
}

function go(item: Row | undefined) {
  if (!item) return
  window.location.href = item.url
}

function onKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    visible.value ? close() : open()
    return
  }
  if (!visible.value) return
  if (event.key === 'Escape') {
    close()
  } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    const count = rows.value.length
    if (!count) return
    const delta = event.key === 'ArrowDown' ? 1 : -1
    active.value = (active.value + delta + count) % count
  } else if (event.key === 'Enter') {
    go(rows.value[active.value])
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  window.clearTimeout(suggestTimer)
  window.clearTimeout(bodyTimer)
  document.body.style.overflow = ''
})
</script>

<template>
  <div v-if="visible" class="md-search-panel" @click.self="close">
    <div class="md-search-panel__dialog" role="dialog" aria-modal="true" aria-label="全局搜索">
      <div class="md-search-panel__input">
        <input
          ref="inputEl"
          v-model="keyword"
          type="search"
          placeholder="搜索知识库、文档、标签…"
          autocomplete="off"
          @input="onInput"
        >
        <kbd>Esc</kbd>
      </div>
      <div class="md-search-panel__body">
        <p v-if="loading && !rows.length" class="md-search-panel__empty">搜索中…</p>
        <p v-else-if="!hasKeyword" class="md-search-panel__empty">输入关键字开始搜索，↑↓ 选择，Enter 打开</p>
        <p v-else-if="!rows.length && !bodyPending" class="md-search-panel__empty">没有匹配的结果</p>
        <template v-else>
          <div v-for="group in groups" :key="group.title" class="md-search-group">
            <div class="md-search-group__title">
              {{ group.title }}
              <small v-if="group.title === '正文匹配' && bodyTotal > group.list.length">
                共 {{ bodyTotal }} 处，显示前 {{ group.list.length }} 条
              </small>
            </div>
            <div
              v-for="(item, index) in group.list"
              :key="`${group.title}-${index}`"
              class="md-search-item"
              :class="{ 'is-active': group.offset + index === active }"
              @click="go(item)"
            >
              <strong>{{ item.title }}</strong>
              <small>{{ item.subtitle }}</small>
              <p v-if="item.snippet" class="md-search-item__snippet">{{ item.snippet }}</p>
            </div>
          </div>
          <p v-if="bodyPending" class="md-search-panel__empty">正在扫描可见知识库的正文…</p>
        </template>
      </div>
    </div>
  </div>
</template>
