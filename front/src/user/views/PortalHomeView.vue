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
 *
 * <p>页签是分段控件：选中档抬起一张白片、改用主色文字，数字跟在标签后。
 * 三档各配一个色相会把工具条变成彩带，所以颜色只在选中态出现。</p>
 */
const TABS = [
  { key: 'all', label: '全部' },
  { key: 'public', label: '共享' },
  { key: 'private', label: '加密' }
] as const

type FilterKey = (typeof TABS)[number]['key']

const filter = ref<FilterKey>('all')
const sort = ref<'updated' | 'created' | 'name'>('updated')
const keyword = ref('')
const viewMode = ref<'grid' | 'list'>('grid')

/** 搜索框旁的示例词：点一下直接当关键词查，比对着空框想词更省事 */
const HOT_TAGS = ['使用指南', '部署', 'API', '主题']

/** 一次取一批卡片；卡片区滚到底就再取一批，直到没有更多 */
const BATCH = 20
const kbs = ref<KbVO[]>([])
const total = ref(0)
/** 列表请求在飞（首屏之后按页续拉也算） */
const busy = ref(false)
/**
 * 滚动容器是全站唯一的 {@code #md-view}。
 *
 * <p>门户改成文档流：Hero、能力条、工具条、卡片、页脚依次排下来，由外层滚动区统一滚。
 * 无限加载因此要监听它，而不是卡片自己那一层 —— 卡片区不再是独立滚动盒。</p>
 */
const viewEl = ref<HTMLElement | null>(null)
/** 列表区本身（非吸顶），用来算「结果从哪开始」，见 revealResults */
const browseEl = ref<HTMLElement | null>(null)
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

/** 区块标题跟着筛选与搜索词走：筛过的列表如果还标「全部知识库」，文字和结果自相矛盾 */
const browseTitle = computed(() => {
  const kw = keyword.value.trim()
  if (kw) return `搜索「${kw}」`
  if (filter.value === 'public') return '共享知识库'
  if (filter.value === 'private') return '加密知识库'
  return '全部知识库'
})

onMounted(async () => {
  viewEl.value = document.getElementById('md-view')
  viewEl.value?.addEventListener('scroll', onViewScroll, { passive: true })
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

/** 换了筛选条件就从头查；页面滚动位置不动，工具条本身是吸顶的 */
async function runQuery() {
  const seq = ++querySeq
  busy.value = true
  try {
    const res = await portalApi.kbs(query(1))
    if (seq !== querySeq) return
    kbs.value = res.list
    total.value = res.total
    fetchedPages = 1
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
 * 贴着滚动区底部就续拉，直到装满或真的没有更多。
 *
 * <p>触发用滚动事件而不是 IntersectionObserver 的哨兵：IO 在停止渲染的地方（后台标签页）
 * 一次都不回调，用户切回来就会发现列表停在半截；滚动事件跟着真实的滚动每次都发。
 * 这里循环而不是拉一次就走，是因为一屏可能装得下两批卡片（大屏 + 卡片很少），得接着补。</p>
 */
async function fillViewport(seq: number) {
  for (let i = 0; i < 20 && seq === querySeq && hasMore.value && !busy.value; i++) {
    const area = viewEl.value
    if (!area || !nearBottom(area)) break
    const before = kbs.value.length
    await fetchNext(seq)
    if (kbs.value.length === before) break
    await nextTick()
  }
}

function onViewScroll() {
  const area = viewEl.value
  if (area && hasMore.value && !busy.value && nearBottom(area)) void fillViewport(querySeq)
}

/**
 * 筛选条件一变就把结果区拉回视线。
 *
 * <p>工具条吸顶，用户常常是在列表深处换页签、改排序。此时若原地重查，
 * 新结果会从「当前滚到哪儿」开始显示，看到的是列表腰部甚至页脚。
 * 只有已经翻过列表起点时才回滚，停在 Hero 上的人不该被拽下来。</p>
 */
function revealResults() {
  const area = viewEl.value
  const browse = browseEl.value
  if (!area || !browse) return
  const top = browse.getBoundingClientRect().top - area.getBoundingClientRect().top + area.scrollTop
  if (area.scrollTop > top) area.scrollTo({ top, behavior: 'smooth' })
}

/**
 * 提交搜索或点热门词后把列表拉进视线。
 *
 * <p>输入本身已经触发实时筛选，按钮不做第二次请求；它只是解决「结果在首屏以下、
 * 用户敲完回车却没看见变化」的问题，所以这里只负责滚动。</p>
 */
function goBrowse() {
  const area = viewEl.value
  const browse = browseEl.value
  if (!area || !browse) return
  const top = browse.getBoundingClientRect().top - area.getBoundingClientRect().top + area.scrollTop - 12
  area.scrollTo({ top: Math.max(0, top), behavior: 'smooth' })
}

function pickHot(tag: string) {
  if (keyword.value === tag) void runQuery()
  else keyword.value = tag
  goBrowse()
}

watch([filter, sort], () => {
  revealResults()
  void runQuery()
})
// 搜索框逐字触发会打爆接口，停 300ms 再查
watch(keyword, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    revealResults()
    void runQuery()
  }, 300)
})

onBeforeUnmount(() => {
  window.clearTimeout(searchTimer)
  viewEl.value?.removeEventListener('scroll', onViewScroll)
})

function switchView(mode: 'grid' | 'list') {
  viewMode.value = mode
  void settings.patch({ kbView: mode })
}
</script>

<template>
  <!-- 首屏骨架：Hero、能力条与卡片按真实尺寸占位，加载完成时页面不跳 -->
  <div v-if="loading" class="md-portal" aria-busy="true" aria-label="加载中">
    <div class="md-portal__inner">
      <section class="md-hero">
        <div class="md-hero__grid">
          <div class="md-hero__intro">
            <span class="md-skel md-skel--eyebrow" />
            <span class="md-skel md-skel--title" />
            <span class="md-skel md-skel--lead" />
            <span class="md-skel md-skel--search" />
            <span class="md-skel md-skel--hot" />
          </div>
          <div class="md-hero__art">
            <span class="md-skel md-skel--art" />
          </div>
        </div>
        <span class="md-skel md-skel--features" />
      </section>
      <div class="md-skel-grid">
        <span v-for="i in 6" :key="i" class="md-skel md-skel--card" />
      </div>
    </div>
  </div>

  <StatusView v-else-if="failed || !home" code="500" title="页面加载失败" message="请稍后重试，或检查后端服务是否已启动。" />

  <main v-else class="md-portal">
    <div class="md-portal__inner">
      <!--
        Hero 是整页的门面：左边一句主张、一个搜索与几个示例词，右边一幅水彩山湖，
        底下一排能力条把「这套系统能做什么」说清。浅色底、青绿点睛，不用深色大块压人。
      -->
      <section class="md-hero">
        <div class="md-hero__grid">
          <div class="md-hero__intro">
            <p class="md-hero__eyebrow">
              <Icon name="bolt" :size="13" />轻量 · 简约 · 高效
            </p>
            <h1 class="md-hero__title">探索知识，连接<span class="md-hero__title-mark">灵感</span></h1>
            <p class="md-hero__lead">MiniDocs 为你打造的轻量级知识库平台，让知识更有价值</p>

            <form class="md-search" role="search" @submit.prevent="goBrowse">
              <Icon name="search" :size="20" />
              <input
                v-model="keyword"
                type="search"
                placeholder="搜索知识库名称、描述或标签"
                autocomplete="off"
                aria-label="搜索知识库"
              >
              <button v-if="keyword" type="button" class="md-search__clear" aria-label="清空搜索" @click="keyword = ''">
                <Icon name="close" :size="14" />
              </button>
              <button type="submit" class="md-search__submit">搜索</button>
            </form>

            <div class="md-hero__hot">
              <span class="md-hero__hot-label">热门搜索</span>
              <button v-for="tag in HOT_TAGS" :key="tag" type="button" class="md-hot" @click="pickHot(tag)">
                {{ tag }}
              </button>
            </div>
          </div>

          <!-- 水彩山湖：雾山、湖面、小船与飞鸟，边缘用模糊蒙版做出晕染感 -->
          <div class="md-hero__art" aria-hidden="true">
            <svg class="md-hero__art-svg" viewBox="0 0 560 440" fill="none">
              <defs>
                <linearGradient id="mdArtSky" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="var(--art-sky-top, #f6fdfa)" />
                  <stop offset="1" stop-color="var(--art-sky-bottom, #ddf3ec)" />
                </linearGradient>
                <linearGradient id="mdArtFar" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="var(--art-far-top, #bfe5da)" />
                  <stop offset="1" stop-color="var(--art-far-bottom, #9cd0c3)" />
                </linearGradient>
                <linearGradient id="mdArtMid" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="var(--art-mid-top, #88cabb)" />
                  <stop offset="1" stop-color="var(--art-mid-bottom, #5aa899)" />
                </linearGradient>
                <linearGradient id="mdArtNear" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="var(--art-near-top, #3f9486)" />
                  <stop offset="1" stop-color="var(--art-near-bottom, #1f6b60)" />
                </linearGradient>
                <linearGradient id="mdArtLake" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="var(--art-lake-top, #c3e8de)" />
                  <stop offset="1" stop-color="var(--art-lake-bottom, #8ecabd)" />
                </linearGradient>
                <linearGradient id="mdArtSun" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="var(--art-sun-top, #fff7e5)" />
                  <stop offset="1" stop-color="var(--art-sun-bottom, #f6d9a4)" />
                </linearGradient>
                <radialGradient id="mdArtGlow" cx=".5" cy=".5" r=".5">
                  <stop offset="0" stop-color="var(--art-glow, #7fd8c6)" stop-opacity=".34" />
                  <stop offset="1" stop-color="var(--art-glow, #7fd8c6)" stop-opacity="0" />
                </radialGradient>
                <pattern id="mdArtGrain" width="6" height="6" patternUnits="userSpaceOnUse">
                  <circle cx="1.2" cy="1.2" r=".8" fill="var(--art-grain, #14685c)" opacity=".07" />
                </pattern>
                <filter id="mdArtBlurEdge" x="-20%" y="-20%" width="140%" height="140%">
                  <feGaussianBlur stdDeviation="9" />
                </filter>
                <filter id="mdArtBlurFar" x="-20%" y="-40%" width="140%" height="180%">
                  <feGaussianBlur stdDeviation="7" />
                </filter>
                <filter id="mdArtBlurMid" x="-20%" y="-30%" width="140%" height="160%">
                  <feGaussianBlur stdDeviation="3.2" />
                </filter>
                <filter id="mdArtBlurMist" x="-20%" y="-60%" width="140%" height="220%">
                  <feGaussianBlur stdDeviation="12" />
                </filter>
                <filter id="mdArtBlurSun" x="-60%" y="-60%" width="220%" height="220%">
                  <feGaussianBlur stdDeviation="10" />
                </filter>
                <mask id="mdArtMask">
                  <path
                    d="M56 216C56 122 126 56 254 48 382 40 500 100 512 196c12 96-50 184-178 200-128 16-242-22-268-96-8-24-10-54-10-84Z"
                    fill="#fff"
                    filter="url(#mdArtBlurEdge)"
                  />
                </mask>
              </defs>

              <circle cx="300" cy="220" r="212" fill="url(#mdArtGlow)" />

              <g mask="url(#mdArtMask)">
                <rect x="30" y="30" width="500" height="390" fill="url(#mdArtSky)" />

                <!-- 太阳与光晕 -->
                <circle cx="410" cy="128" r="78" fill="var(--art-halo, #f7d9a4)" opacity=".22" filter="url(#mdArtBlurSun)" />
                <circle cx="410" cy="128" r="34" fill="url(#mdArtSun)" opacity=".95" />

                <!-- 远山：一整片浅雾青，边缘化开 -->
                <path
                  d="M24 286 104 188l58 54 62-78 56 66 58-44 52 62 42-30 64 68v210H24Z"
                  fill="url(#mdArtFar)"
                  opacity=".9"
                  filter="url(#mdArtBlurFar)"
                />
                <ellipse cx="290" cy="288" rx="272" ry="24" fill="var(--art-mist, #f8fefc)" opacity=".75" filter="url(#mdArtBlurMist)" />
                <!-- 两片淡色水洗，让山脚有「颜料化开」的层次 -->
                <ellipse cx="170" cy="250" rx="118" ry="66" fill="var(--art-wash-1, #c9ece2)" opacity=".32" filter="url(#mdArtBlurMist)" />
                <ellipse cx="412" cy="240" rx="96" ry="54" fill="var(--art-wash-2, #a6dbce)" opacity=".26" filter="url(#mdArtBlurMist)" />

                <!-- 中景山与湖岸 -->
                <path
                  d="M24 332 96 272l60 40 74-64 66 58 56-30 50 44 54-24 60 42v170H24Z"
                  fill="url(#mdArtMid)"
                  opacity=".96"
                  filter="url(#mdArtBlurMid)"
                />

                <!-- 湖面与倒影 -->
                <rect x="30" y="340" width="500" height="80" fill="url(#mdArtLake)" opacity=".95" />
                <ellipse cx="300" cy="352" rx="210" ry="10" fill="var(--art-mist, #f8fefc)" opacity=".65" filter="url(#mdArtBlurMist)" />
                <path
                  d="M120 372h58M196 386h84M324 370h64M270 398h96M150 404h48"
                  stroke="var(--art-reflection, #ecfaf5)"
                  stroke-width="3"
                  stroke-linecap="round"
                  opacity=".7"
                />

                <!-- 近岸 -->
                <path
                  d="M24 402c64-24 118-22 170-12 62 12 116 4 168-8 46-10 92-6 144 10v62H24Z"
                  fill="url(#mdArtNear)"
                  filter="url(#mdArtBlurMid)"
                />
                <path
                  d="M24 410c74-18 132-14 186-2 60 14 122 4 176-8 52-12 104-4 144 8v46H24Z"
                  fill="url(#mdArtNear)"
                  opacity=".92"
                />

                <!-- 小船：湖面上的那一点「人」的气息 -->
                <g>
                  <ellipse cx="277" cy="374" rx="40" ry="6" fill="var(--art-ink, #14564d)" opacity=".16" filter="url(#mdArtBlurMid)" />
                  <path d="M246 356c14 10 46 10 62 0l-6 12c-12 8-38 8-50 0z" fill="var(--art-ink, #14564d)" />
                  <path d="M278 330v26" stroke="var(--art-ink, #14564d)" stroke-width="3" stroke-linecap="round" />
                  <path d="M280 332c10 4 16 10 18 18-8 2-14 0-18-4z" fill="var(--art-sail, #2c7d6f)" opacity=".9" />
                  <circle cx="268" cy="342" r="5" fill="var(--art-ink, #14564d)" />
                  <path d="M264 348h10" stroke="var(--art-ink, #14564d)" stroke-width="3" stroke-linecap="round" />
                </g>

                <!-- 飞鸟 -->
                <g stroke="var(--art-bird, #3d7f75)" stroke-width="2.6" stroke-linecap="round" fill="none" opacity=".8">
                  <path d="M104 118c5-6 10-6 15 0 5-6 10-6 15 0" />
                  <path d="M158 96c4-5 8-5 12 0 4-5 8-5 12 0" opacity=".7" />
                  <path d="M126 148c3-4 6-4 9 0 3-4 6-4 9 0" opacity=".55" />
                </g>

                <!-- 芦苇与纸张颗粒 -->
                <g stroke="var(--art-reed, #1c6458)" stroke-width="2.4" stroke-linecap="round" opacity=".75">
                  <path d="M78 414c4-18 6-32 5-46M92 416c0-14 2-26 7-38M104 418c-2-12-6-22-12-30" />
                  <path d="M470 416c-4-16-4-28 0-40M486 418c2-12 0-22-5-32" />
                </g>
                <rect x="30" y="30" width="500" height="390" fill="url(#mdArtGrain)" opacity=".6" />
              </g>

              <!-- 画外点缀 -->
              <circle cx="470" cy="330" r="6" fill="var(--art-dot, #2c7d6f)" opacity=".5" />
              <circle cx="92" cy="180" r="4" fill="var(--art-accent, #f0c98a)" opacity=".7" />
              <path d="M452 70c2 12 7 17 19 19-12 2-17 7-19 19-2-12-7-17-19-19 12-2 17-7 19-19z" fill="var(--art-accent, #f0c98a)" opacity=".9" />
            </svg>
          </div>
        </div>

        <!-- 能力条：磨砂玻璃底、五格实心图标，发丝线分隔 -->
        <ul class="md-features">
          <li class="md-feature">
            <span class="md-feature__icon"><Icon name="bookOpen" :size="28" /></span>
            <span class="md-feature__text"><strong>海量文档</strong><small>优质知识，随时获取</small></span>
          </li>
          <li class="md-feature">
            <span class="md-feature__icon"><Icon name="layers" :size="28" /></span>
            <span class="md-feature__text"><strong>多库管理</strong><small>独立知识库，灵活配置</small></span>
          </li>
          <li class="md-feature">
            <span class="md-feature__icon"><Icon name="tagSolid" :size="28" /></span>
            <span class="md-feature__text"><strong>标签分类</strong><small>精准分类，快速查找</small></span>
          </li>
          <li class="md-feature">
            <span class="md-feature__icon"><Icon name="shieldCheck" :size="28" /></span>
            <span class="md-feature__text"><strong>权限控制</strong><small>安全可控，放心使用</small></span>
          </li>
          <li class="md-feature">
            <span class="md-feature__icon"><Icon name="githubBadge" :size="28" /></span>
            <span class="md-feature__text"><strong>开源共建</strong><small>开源项目，携手共建</small></span>
          </li>
        </ul>
      </section>

      <section ref="browseEl" class="md-browse" aria-label="知识库列表">
        <!-- 区块标题跟着筛选走，让「正在看什么」在长列表上方始终说得清 -->
        <header class="md-browse__head">
          <div class="md-browse__lead">
            <span class="md-browse__bar" aria-hidden="true" />
            <h2 class="md-browse__title">{{ browseTitle }}</h2>
          </div>
          <PortalStats v-if="stats" :stats="stats" />
        </header>

        <!-- 工具条吸在滚动区顶部：向下翻卡片时筛选与排序始终在手边 -->
        <div class="md-toolbar">
          <div class="md-tabs" role="tablist" aria-label="按分享状态筛选">
            <button
              v-for="tab in tabs"
              :key="tab.key"
              type="button"
              role="tab"
              class="md-tab"
              :class="{ 'is-active': filter === tab.key }"
              :aria-selected="filter === tab.key"
              @click="filter = tab.key"
            >
              {{ tab.label }}<em>{{ counts[tab.key] }}</em>
            </button>
          </div>

          <div class="md-toolbar__right">
            <label class="md-sort" title="排序方式">
              <select v-model="sort" class="md-sort__select">
                <option value="updated">最近更新</option>
                <option value="created">创建时间</option>
                <option value="name">名称</option>
              </select>
              <Icon name="chevronDown" :size="13" />
            </label>

            <div class="md-viewswitch" role="group" aria-label="视图切换">
              <button type="button" :class="{ 'is-active': viewMode === 'grid' }" title="网格视图" @click="switchView('grid')">
                <Icon name="grid" :size="15" />
              </button>
              <button type="button" :class="{ 'is-active': viewMode === 'list' }" title="列表视图" @click="switchView('list')">
                <Icon name="list" :size="15" />
              </button>
            </div>
          </div>
        </div>

        <!-- key 带上视图模式：换布局时重建卡片，免得开着的「更多」菜单按旧位置悬在半空 -->
        <div v-if="kbs.length" class="md-cards" :class="{ 'is-list': viewMode === 'list' }">
          <KbCard v-for="kb in kbs" :key="`${viewMode}-${kb.id}`" :kb="kb" />
        </div>

        <p v-if="kbs.length && busy" class="md-cards__end">加载中…</p>
        <p v-else-if="kbs.length && !hasMore" class="md-cards__end">已经到底了</p>
        <p v-else-if="!kbs.length && busy" class="md-empty-hint">加载中…</p>
        <div v-else class="md-empty-state">
          <span class="md-empty-state__icon" aria-hidden="true">
            <Icon name="books" :size="24" />
          </span>
          <p class="md-empty-state__title">{{ isFiltered ? '没有匹配的知识库' : '还没有知识库' }}</p>
          <p class="md-empty-state__text">
            {{ isFiltered ? '换一个筛选条件，或切回「全部」看看。' : '发布第一个知识库后，它会出现在这里。' }}
          </p>
        </div>
      </section>
    </div>

    <SiteFooter />
  </main>
</template>
