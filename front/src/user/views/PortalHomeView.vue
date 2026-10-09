<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from '@/user/components/Icon.vue'
import KbCard from '@/user/components/KbCard.vue'
import PortalStats from '@/user/components/PortalStats.vue'
import SiteFooter from '@/user/components/SiteFooter.vue'
import StatusView from '@/user/views/StatusView.vue'
import { portalApi } from '@/user/api'
import { useSessionStore } from '@/user/stores/session'
import { useSettingsStore } from '@/shared/stores/settings'
import { useBodyClasses } from '@/user/composables/useBodyClasses'
import { appHref } from '@/shared/appBase'
import type { KbVO } from '@/shared/api/types'
import type { PortalHomeVO } from '@/user/api/types'

const session = useSessionStore()
const settings = useSettingsStore()

const home = ref<PortalHomeVO | null>(null)
const loading = ref(true)
const failed = ref(false)

useBodyClasses(computed(() => (failed.value || (!loading.value && !home.value) ? ['md-page--error'] : [])))

/**
 * 页签按「陌生人怎么进来」两态各给一个。
 *
 * <p>分享等同于发布：门户只出已发布的库，共享 = 分享未加密、加密 = 分享已加密（需口令）。
 * 可见范围那一档（所有登录用户 / 本组织成员 / 仅维护名单）不决定门户列表，
 * 所以这里既没有「本组织」，也不叫「公开 / 私有」——那两个词留给后台的权限语境，
 * 免得读者把「门户上要口令」理解成「只有某类人能看」。</p>
 */
/*
 * 三档各配一个色相：全部=蓝、共享=绿、加密=琥珀。
 *
 * <p>语义上也有对应 —— 共享是「可公开读」用绿，加密是「要口令」用琥珀（一点警示味）。
 * 令牌与侧栏、工作区工具条共用同一套，所以同一个入口在三个地方颜色一致。</p>
 */
const TABS = [
  { key: 'all', label: '全部', icon: 'grid', tone: 'blue' },
  { key: 'public', label: '共享', icon: 'globe', tone: 'green' },
  { key: 'private', label: '加密', icon: 'lock', tone: 'amber' }
] as const

type FilterKey = (typeof TABS)[number]['key']

const filter = ref<FilterKey>('all')
const sort = ref<'updated' | 'created' | 'name'>('updated')
const keyword = ref('')
const viewMode = ref<'grid' | 'list'>('grid')

/** 一次取一批卡片；卡片区滚到底就再取一批，直到没有更多 */
const BATCH = 20
const kbs = ref<KbVO[]>([])
const total = ref(0)
/** 列表请求在飞（首屏之后按页续拉也算） */
const busy = ref(false)
const cardsEl = ref<HTMLElement | null>(null)
/** 只认最后一次查询：筛选连改时迟到的旧响应会盖掉新结果 */
let querySeq = 0
let fetchedPages = 0
let searchTimer: number | undefined

const stats = computed(() => home.value?.stats)

/** 页签对所有人一致：私有库也在门户展示（带锁标，点进去输口令），登录与否不改变可选范围 */
const tabs = TABS

/** 页签计数取统计接口的口径：列表是按页取的，本地数不出全量 */
const counts = computed(() => ({
  all: stats.value?.kbTotal ?? 0,
  public: stats.value?.kbPublic ?? 0,
  private: stats.value?.kbPrivate ?? 0
}))

const hasMore = computed(() => kbs.value.length < total.value)

/** 空列表时用来分「真的没有」还是「筛没了」 */
const isFiltered = computed(() => !!keyword.value.trim() || filter.value !== 'all')

onMounted(async () => {
  viewMode.value = settings.value.kbView === 'list' ? 'list' : 'grid'
  try {
    const data = await portalApi.home()
    home.value = data
    // 首页顺带把登录态灌进 store，避免顶栏再发一次 /auth/me
    session.set(data.user ?? null)
  } catch {
    failed.value = true
    return
  } finally {
    loading.value = false
  }
  await runQuery()
})

function query(page: number) {
  return {
    page,
    size: BATCH,
    sort: sort.value,
    access: filter.value === 'all' ? undefined : filter.value,
    keyword: keyword.value.trim() || undefined
  }
}

/** 换了筛选条件就从头查，并把卡片区滚回顶部 */
async function runQuery() {
  const seq = ++querySeq
  busy.value = true
  try {
    const res = await portalApi.kbs(query(1))
    if (seq !== querySeq) return
    kbs.value = res.list
    total.value = res.total
    fetchedPages = 1
    if (cardsEl.value) cardsEl.value.scrollTop = 0
  } catch {
    if (seq === querySeq) {
      kbs.value = []
      total.value = 0
    }
  } finally {
    if (seq === querySeq) busy.value = false
  }
  await nextTick()
  void fillViewport(seq)
}

/** 续拉一页，返回是否真拿到了新卡片 */
async function fetchNext(seq: number) {
  busy.value = true
  try {
    const res = await portalApi.kbs(query(fetchedPages + 1))
    if (seq !== querySeq) return false
    const seen = new Set(kbs.value.map((kb) => kb.id))
    // 去重：两次翻页之间有人改了名称或时间，整集会位移，同一条可能两头都出现
    const fresh = res.list.filter((kb) => !seen.has(kb.id))
    kbs.value = [...kbs.value, ...fresh]
    total.value = res.total
    fetchedPages += 1
    return fresh.length > 0
  } catch {
    return false
  } finally {
    if (seq === querySeq) busy.value = false
  }
}

/** 距底不足这个数就把下一页拉上来，免得用户看到底了还在等 */
const PREFETCH = 260

function nearBottom(area: HTMLElement) {
  return area.scrollTop + area.clientHeight >= area.scrollHeight - PREFETCH
}

/**
 * 贴着卡片区底部就续拉，直到装满或真的没有更多。
 *
 * <p>触发用滚动事件而不是 IntersectionObserver 的哨兵：IO 在停止渲染的地方（后台标签页）
 * 一次都不回调，用户切回来就会发现列表停在半截；滚动事件跟着真实的滚动每次都发。
 * 这里循环而不是拉一次就走，是因为一屏可能装得下两批卡片（大屏 + 卡片很少），得接着补。</p>
 */
async function fillViewport(seq: number) {
  for (let i = 0; i < 20 && seq === querySeq && hasMore.value && !busy.value; i++) {
    const area = cardsEl.value
    if (!area || !nearBottom(area)) break
    const before = kbs.value.length
    await fetchNext(seq)
    if (kbs.value.length === before) break
    await nextTick()
  }
}

function onCardsScroll() {
  const area = cardsEl.value
  if (area && hasMore.value && !busy.value && nearBottom(area)) void fillViewport(querySeq)
}

watch([filter, sort], () => void runQuery())
// 搜索框逐字触发会打爆接口，停 300ms 再查
watch(keyword, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => void runQuery(), 300)
})

onBeforeUnmount(() => window.clearTimeout(searchTimer))

function switchView(mode: 'grid' | 'list') {
  viewMode.value = mode
  void settings.patch({ kbView: mode })
}
</script>

<template>
  <p v-if="loading" class="md-empty-hint" style="padding-top: 80px;">加载中…</p>
  <StatusView v-else-if="failed || !home" code="500" title="页面加载失败" message="请稍后重试，或检查后端服务是否已启动。" />

  <main v-else class="md-portal">
    <PortalStats v-if="stats" :stats="stats" />

    <section class="md-filters">
      <div class="md-seg" role="tablist">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          role="tab"
          class="md-seg__btn"
          :class="[`md-seg__btn--${tab.tone}`, { 'is-active': filter === tab.key }]"
          @click="filter = tab.key"
        >
          <Icon :name="tab.icon" :size="14" />{{ tab.label }}<em>{{ counts[tab.key] }}</em>
        </button>
      </div>

      <!--
        搜索框跟着右侧动作组一起靠右：它做的也是「在当前列表上筛」，和排序 / 视图 / 新建是一类动作，
        贴着它们比贴着页签更好找；原来紧挨页签、把整条空白留在右侧，视线得横跨整条工具条。
      -->
      <div class="md-filters__right">
        <label class="md-search-inline">
          <Icon name="search" :size="15" />
          <input v-model="keyword" type="search" placeholder="搜索知识库名称、描述或标签..." autocomplete="off">
        </label>

        <label class="md-select-wrap">
          <select v-model="sort" class="md-select">
            <option value="updated">最近更新</option>
            <option value="created">创建时间</option>
            <option value="name">名称</option>
          </select>
          <Icon name="chevronDown" :size="14" />
        </label>

        <div class="md-viewswitch">
          <button type="button" :class="{ 'is-active': viewMode === 'grid' }" title="网格视图" @click="switchView('grid')">
            <Icon name="grid" :size="15" />
          </button>
          <button type="button" :class="{ 'is-active': viewMode === 'list' }" title="列表视图" @click="switchView('list')">
            <Icon name="list" :size="15" />
          </button>
        </div>

        <!--
          新建入口在筛选栏，不在顶栏：顶栏每页都在，而「建库」是站在这张列表上才有的动作。
          地址带 ?new=1，由工作区落地页解析组织后打开新建弹窗（见 EntryView / KbManageView）。
        -->
        <a v-if="session.loggedIn" class="md-btn md-btn--primary" :href="appHref('/console?new=1')">
          <Icon name="plus" :size="14" />新建知识库
        </a>
      </div>
    </section>

    <div
      v-if="kbs.length"
      ref="cardsEl"
      class="md-cards"
      :class="{ 'is-list': viewMode === 'list' }"
      @scroll.passive="onCardsScroll"
    >
      <!-- key 带上视图模式：换布局时重建卡片，免得开着的「更多」菜单按旧位置悬在半空 -->
      <KbCard v-for="kb in kbs" :key="`${viewMode}-${kb.id}`" :kb="kb" />
      <p v-if="busy" class="md-cards__end">加载中…</p>
      <p v-else-if="!hasMore" class="md-cards__end">没有更多了</p>
    </div>
    <p v-else-if="busy" class="md-empty-hint">加载中…</p>
    <div v-else class="md-empty-state">
      <span class="md-empty-state__icon" aria-hidden="true">
        <Icon name="books" :size="26" />
      </span>
      <p class="md-empty-hint">{{ isFiltered ? '没有匹配的知识库' : '还没有知识库' }}</p>
      <a v-if="session.loggedIn && !isFiltered" class="md-btn md-btn--primary" :href="appHref('/console?new=1')">
        <Icon name="plus" :size="14" />建第一个库
      </a>
    </div>

    <SiteFooter />
  </main>
</template>
