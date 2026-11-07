<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { kbApi } from '@/admin/api'
import AdminPageHeader from '@/admin/components/AdminPageHeader.vue'
import MdIcon from '@/admin/components/MdIcon.vue'
import HelpTip from '@/shared/components/HelpTip.vue'
import type { KbVO, StatsVO } from '@/shared/api/types'
import { portalKbUrl } from '@/shared/portal'
import { appHref } from '@/shared/appBase'
import { visibilityHint, visibilityLabel, visibilityChipClass } from '@/shared/visibility'
import { useAuthStore } from '@/admin/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const stats = ref<StatsVO | null>(null)
const recent = ref<KbVO[]>([])
const loading = ref(true)

const org = String(route.params.org || '')

onMounted(async () => {
  try {
    const [statsData, page] = await Promise.all([
      kbApi.stats(),
      kbApi.page({ page: 1, size: 6, sort: 'updated' })
    ])
    stats.value = statsData
    recent.value = page.list
  } finally {
    loading.value = false
  }
})

function pct(part: number, total: number) {
  return total > 0 ? Math.round((part / total) * 100) : 0
}

const kbTotal = computed(() => stats.value?.kbTotal ?? 0)
const docTotal = computed(() => stats.value?.docTotal ?? 0)
const docAvg = computed(() => (kbTotal.value ? Math.round(docTotal.value / kbTotal.value) : 0))
const publicKbPct = computed(() => pct(stats.value?.kbPublic ?? 0, kbTotal.value))
const publicDocPct = computed(() => pct(stats.value?.publicDocTotal ?? 0, docTotal.value))

const visibilityRows = computed(() => [
  { key: 'public', label: '所有登录用户', value: stats.value?.kbPublic ?? 0, pct: publicKbPct.value },
  { key: 'org', label: '本组织成员', value: stats.value?.kbOrg ?? 0, pct: pct(stats.value?.kbOrg ?? 0, kbTotal.value) },
  { key: 'private', label: '仅维护名单', value: stats.value?.kbPrivate ?? 0, pct: pct(stats.value?.kbPrivate ?? 0, kbTotal.value) }
])

/** 同一个库每次进来都是同一个色块：颜色由 id + slug 固定，不随排序变化 */
function tileTone(kb: KbVO) {
  const key = `${kb.id}${kb.slug || ''}`
  let hash = 0
  for (let i = 0; i < key.length; i += 1) hash = (hash * 31 + key.charCodeAt(i)) >>> 0
  return hash % 6
}

function coverSrc(kb: KbVO) {
  return kb.coverSrc || ''
}

function hideBrokenImage(event: Event) {
  ;(event.target as HTMLImageElement).style.display = 'none'
}

function openWorkspace(kb: KbVO) {
  router.push({ name: 'kb-workspace', params: { org, slug: kb.slug } })
}

function openPortal(slug: string) {
  window.open(appHref(portalKbUrl(org, slug)), '_blank')
}
</script>

<template>
  <div v-loading="loading" class="md-page">
    <AdminPageHeader
      hero
      icon="chart"
      eyebrow="组织管理"
      title="系统概览"
      description="汇总当前账号可见的知识库与文档，最近更新与可见范围一目了然。"
    >
      <template #actions>
        <el-button type="primary" @click="router.push({ name: 'kbs', params: { org } })">
          <MdIcon name="books" :size="15" />全部知识库
        </el-button>
      </template>
    </AdminPageHeader>

    <section class="md-ov-stats">
      <article class="md-ov-stat md-ov-stat--teal">
        <span class="md-ov-stat__icon"><MdIcon name="books" :size="20" /></span>
        <div class="md-ov-stat__main">
          <p class="md-ov-stat__label">知识库总数</p>
          <p class="md-ov-stat__value">{{ kbTotal }}<small>个</small></p>
          <p class="md-ov-stat__hint">本月新增 {{ stats?.kbTotalDelta ?? 0 }} 个</p>
        </div>
      </article>
      <article class="md-ov-stat md-ov-stat--violet">
        <span class="md-ov-stat__icon"><MdIcon name="file" :size="20" /></span>
        <div class="md-ov-stat__main">
          <p class="md-ov-stat__label">文档总数</p>
          <p class="md-ov-stat__value">{{ docTotal }}<small>篇</small></p>
          <p class="md-ov-stat__hint">平均每库 {{ docAvg }} 篇</p>
        </div>
      </article>
      <article class="md-ov-stat md-ov-stat--blue">
        <span class="md-ov-stat__icon"><MdIcon name="globe" :size="20" /></span>
        <div class="md-ov-stat__main">
          <p class="md-ov-stat__label">
            公开可见知识库
            <HelpTip text="可见范围为「所有登录用户」的知识库。它表示平台内谁能读，与首页上是否已公开分享无关。" />
          </p>
          <p class="md-ov-stat__value">{{ stats?.kbPublic ?? 0 }}<small>个</small></p>
          <p class="md-ov-stat__hint">占全部 {{ publicKbPct }}%</p>
        </div>
      </article>
      <article class="md-ov-stat md-ov-stat--amber">
        <span class="md-ov-stat__icon"><MdIcon name="eye" :size="20" /></span>
        <div class="md-ov-stat__main">
          <p class="md-ov-stat__label">
            公开可见文档
            <HelpTip text="「所有登录用户」可见的知识库里的文档总数；不等于首页已公开的文档数。" />
          </p>
          <p class="md-ov-stat__value">{{ stats?.publicDocTotal ?? 0 }}<small>篇</small></p>
          <p class="md-ov-stat__hint">占全部文档 {{ publicDocPct }}%</p>
        </div>
      </article>
    </section>

    <div class="md-overview-layout">
      <section class="md-card-panel md-ov-panel">
        <div class="md-panel-head">
          <div class="md-panel-head__text">
            <h2><MdIcon name="clock" :size="15" />最近更新</h2>
            <p class="md-panel-desc">按最后修改时间排序，最多显示 6 个知识库。</p>
          </div>
          <span class="md-spacer" />
          <el-button size="small" link class="md-ov-all" @click="router.push({ name: 'kbs', params: { org } })">
            查看全部<MdIcon name="arrow-right" :size="14" />
          </el-button>
        </div>
        <div class="md-panel-body">
          <div v-if="!loading && recent.length === 0" class="md-empty">
            <span class="md-empty__icon"><MdIcon name="books" :size="22" /></span>
            <div class="md-empty__title">还没有知识库</div>
            <p class="md-empty__desc">去知识库管理创建第一个吧，建好后这里会显示最近更新的条目。</p>
          </div>
          <div v-else class="md-recent-list">
            <div v-for="kb in recent" :key="kb.id" class="md-recent-item">
              <!-- 图标垫在封面下面：封面取不到时 @error 把 img 藏掉，露出图标而不是一个空色块 -->
              <span class="md-tile" :class="`md-tile--t${tileTone(kb)}`">
                <MdIcon name="bookOpen" :size="20" />
                <img v-if="coverSrc(kb)" :src="coverSrc(kb)" alt="" loading="lazy" @error="hideBrokenImage" />
              </span>
              <div class="md-recent-item__main">
                <div class="md-recent-item__title">
                  <span>{{ kb.name }}</span>
                  <span class="md-ad-chip" :class="visibilityChipClass(kb.visibility)" :title="visibilityHint(kb.visibility)">
                    {{ visibilityLabel(kb.visibility) }}
                  </span>
                </div>
                <p class="md-recent-item__desc">{{ kb.description || '暂无描述' }}</p>
                <div class="md-recent-item__meta">
                  <span>{{ kb.docCount }} 篇</span>
                  <span>{{ kb.updatedText }}</span>
                </div>
              </div>
              <div class="md-recent-item__actions">
                <el-button size="small" type="primary" class="md-ov-go" @click="openWorkspace(kb)">
                  进入<MdIcon name="arrow-right" :size="14" />
                </el-button>
                <el-button size="small" type="primary" plain class="md-ov-peek" @click="openPortal(kb.slug)">
                  <MdIcon name="eye" :size="14" />预览
                </el-button>
              </div>
            </div>
          </div>
        </div>
      </section>

      <aside class="md-ov-side">
        <section class="md-card-panel md-ov-panel">
          <div class="md-panel-head">
            <div class="md-panel-head__text">
              <h2>
                可见范围分布
                <HelpTip text="按平台内的读权限分三档，与首页公开（是否分享、是否加密）是两件事。" />
              </h2>
              <p class="md-panel-desc">当前账号可见的 {{ kbTotal }} 个知识库。</p>
            </div>
          </div>
          <div class="md-panel-body">
            <div class="md-ov-split" role="img" :aria-label="`可见范围分布：${visibilityRows.map((row) => `${row.label} ${row.value}`).join('，')}`">
              <span
                v-for="row in visibilityRows"
                v-show="row.value > 0"
                :key="row.key"
                class="md-ov-split__seg"
                :class="`is-${row.key}`"
                :style="{ flexGrow: row.value }"
              />
            </div>
            <ul class="md-ov-vis">
              <li v-for="row in visibilityRows" :key="row.key" class="md-ov-vis__item">
                <span class="md-ov-vis__dot" :class="`is-${row.key}`" aria-hidden="true" />
                <div class="md-ov-vis__text">
                  <strong>{{ row.label }}</strong>
                  <small>{{ row.pct }}%</small>
                </div>
                <span class="md-ov-vis__num">{{ row.value }}<small>个</small></span>
              </li>
            </ul>
          </div>
        </section>

        <section class="md-card-panel md-ov-panel">
          <div class="md-panel-head">
            <div class="md-panel-head__text">
              <h2><MdIcon name="user" :size="15" />当前账号</h2>
              <p class="md-panel-desc">这次登录使用的身份。</p>
            </div>
          </div>
          <div class="md-panel-body">
            <div class="md-profile md-ov-profile">
              <div class="md-profile__top">
                <span class="md-profile__avatar">
                  <img v-if="auth.avatarSrc" :src="auth.avatarSrc" alt="" @error="hideBrokenImage" />
                  <template v-else>{{ (auth.nickname || 'U').slice(0, 1) }}</template>
                </span>
                <div>
                  <div class="md-profile__name">{{ auth.nickname }}</div>
                  <div class="md-profile__handle">@{{ auth.user?.username }}</div>
                </div>
              </div>
              <div class="md-profile__rows">
                <div class="md-profile__row">
                  <span>邮箱</span>
                  <span>{{ auth.user?.email || '未填写' }}</span>
                </div>
                <div class="md-profile__row">
                  <span>平台角色</span>
                  <span>
                    <span :class="['md-ad-chip', auth.isPlatformAdmin ? 'md-ad-chip--admin' : 'md-ad-chip--member']">
                      {{ auth.isPlatformAdmin ? '管理员' : '普通用户' }}
                    </span>
                  </span>
                </div>
                <div class="md-profile__row">
                  <span>可见知识库</span>
                  <span>{{ kbTotal }} 个</span>
                </div>
              </div>
              <el-button class="md-fill" @click="router.push('/account')">
                <MdIcon name="user" :size="14" />账号设置
              </el-button>
            </div>
          </div>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
/*
 * 「最近更新」每行两颗按钮是一对主 / 次：进入是整行唯一的实心重色，预览退成低饱和蓝底。
 * 原来两颗默认小描边按钮并排，重量一样、边框一样，视线不知道落在哪颗上。
 */
.md-recent-item__actions { gap: 6px; }
.md-recent-item__actions :deep(.el-button.md-ov-go),
.md-recent-item__actions :deep(.el-button.md-ov-peek) {
  height: 32px; padding: 0 13px; border-radius: 10px;
  font-size: 12.5px;
}
.md-recent-item__actions :deep(.el-button + .el-button) { margin-left: 0; }

/* 进入：品牌渐变实心，箭头尾随表示「往里走」；悬停只加投影与一点亮度，不换底色 */
.md-recent-item__actions :deep(.el-button.md-ov-go) {
  font-weight: 600;
  background: var(--md-brand-gradient);
  box-shadow: 0 2px 6px color-mix(in srgb, var(--md-primary) 30%, transparent);
  --el-button-bg-color: transparent;
  --el-button-border-color: transparent;
  --el-button-text-color: #fff;
  --el-button-hover-bg-color: transparent;
  --el-button-hover-border-color: transparent;
  --el-button-hover-text-color: #fff;
  --el-button-active-bg-color: transparent;
  --el-button-active-border-color: transparent;
  --el-button-active-text-color: #fff;
}
.md-recent-item__actions :deep(.el-button.md-ov-go:hover) {
  box-shadow: 0 4px 12px color-mix(in srgb, var(--md-primary) 34%, transparent);
  filter: brightness(1.05);
}
.md-recent-item__actions :deep(.md-ov-go .md-icon) { margin-right: 0; margin-left: 6px; }

/*
 * 预览：低饱和天蓝底 + 同色图标，与知识库卡片底栏的次级动作同一套配色语言。
 * 用 plain 是为了避开 admin.css 里那条「无 type 描边按钮悬停转主色」的全局规则
 * （它的特异性高到页面级覆盖压不住），颜色仍由这里的变量说了算。
 */
.md-recent-item__actions :deep(.el-button.md-ov-peek) {
  --act: #0ea5e9;
  font-weight: 550;
  --el-button-bg-color: color-mix(in srgb, var(--act) 12%, transparent);
  --el-button-border-color: color-mix(in srgb, var(--act) 26%, transparent);
  --el-button-text-color: color-mix(in srgb, var(--act) 82%, var(--md-text));
  --el-button-hover-bg-color: color-mix(in srgb, var(--act) 20%, transparent);
  --el-button-hover-border-color: color-mix(in srgb, var(--act) 42%, transparent);
  --el-button-hover-text-color: color-mix(in srgb, var(--act) 92%, var(--md-text));
  --el-button-active-bg-color: color-mix(in srgb, var(--act) 26%, transparent);
  --el-button-active-border-color: color-mix(in srgb, var(--act) 50%, transparent);
  --el-button-active-text-color: color-mix(in srgb, var(--act) 96%, var(--md-text));
}
.md-recent-item__actions :deep(.el-button.md-ov-peek:focus-visible) {
  outline: 2px solid color-mix(in srgb, var(--act) 70%, transparent);
  outline-offset: 1px;
}

/*
 * 面板头的「查看全部」是导航不是动作：幽灵胶囊，平时只有灰字，悬停才染一层主色底，
 * 箭头尾随并轻轻右移。原来那颗描边白底小按钮和行内动作抢重量，放在标题旁显得突兀。
 * 用 link 形态避开 admin.css 那条「无 type 描边按钮悬停转主色边框」的全局规则。
 */
.md-panel-head :deep(.el-button.md-ov-all) {
  height: 30px; padding: 0 12px; border-radius: 999px;
  font-size: 12.5px; font-weight: 600;
  --el-button-bg-color: transparent;
  --el-button-border-color: transparent;
  --el-button-text-color: var(--md-text-2);
}
/*
 * EP 的 link 形态悬停时写的是字面量 background-color: transparent 与
 * --el-button-hover-link-text-color，改 --el-button-hover-* 变量压不住它，
 * 所以这里直接给 hover / active 写字面色，特异性也要高过它那条 :not(.is-disabled):hover。
 */
.md-panel-head :deep(.el-button.md-ov-all:hover) {
  color: var(--md-primary);
  background-color: var(--md-primary-soft);
  border-color: transparent;
}
.md-panel-head :deep(.el-button.md-ov-all:active) {
  color: var(--md-primary-hover);
  background-color: color-mix(in srgb, var(--md-primary) 16%, transparent);
  border-color: transparent;
}
.md-panel-head :deep(.md-ov-all .md-icon) {
  margin-right: 0; margin-left: 6px;
  transition: transform .15s ease;
}
.md-panel-head :deep(.md-ov-all:hover .md-icon) { transform: translateX(2px); }
</style>
