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
            <HelpTip text="可见范围为「所有登录用户」的知识库。它说明平台内谁能读，与门户上是否已分享无关。" />
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
            <HelpTip text="「所有登录用户」可见的知识库里的文档总数；不等于门户已发布的文档数。" />
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
          <el-button size="small" @click="router.push({ name: 'kbs', params: { org } })">
            <MdIcon name="arrow-right" :size="14" />查看全部
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
                <el-button size="small" type="primary" @click="openWorkspace(kb)">
                  <MdIcon name="arrow-right" :size="14" />进入
                </el-button>
                <el-button size="small" @click="openPortal(kb.slug)">
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
                <HelpTip text="按平台内的读权限分三档，与门户发布（是否分享、是否加密）是两件事。" />
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
