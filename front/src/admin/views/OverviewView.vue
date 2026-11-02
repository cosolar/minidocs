<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
      eyebrow="组织管理"
      title="系统概览"
      description="查看知识库规模、最近更新与当前账号信息。"
    >
      <template #actions>
        <el-button @click="router.push({ name: 'kbs', params: { org } })">
          <MdIcon name="books" :size="14" />全部知识库
        </el-button>
      </template>
    </AdminPageHeader>

    <div class="md-ad-stat-grid">
      <div class="md-stat-card">
        <span class="md-stat-card__icon"><MdIcon name="books" :size="19" /></span>
        <div class="md-stat-card__body">
          <div class="md-stat-card__label">知识库总数</div>
          <div class="md-stat-card__value">{{ stats?.kbTotal ?? 0 }}</div>
          <div class="md-stat-card__hint">本月新增 {{ stats?.kbTotalDelta ?? 0 }}</div>
        </div>
      </div>
      <div class="md-stat-card">
        <span class="md-stat-card__icon md-stat-card__icon--blue"><MdIcon name="folder" :size="19" /></span>
        <div class="md-stat-card__body">
          <div class="md-stat-card__label">
            可见范围分布
            <HelpTip text="所有登录用户 / 本组织成员 / 仅维护名单，与门户发布无关。" />
          </div>
          <div class="md-stat-card__value">
            {{ stats?.kbPublic ?? 0 }}<small>/</small>{{ stats?.kbOrg ?? 0 }}<small>/</small>{{ stats?.kbPrivate ?? 0 }}
          </div>
          <div class="md-stat-card__hint">公开 / 组织 / 私有</div>
        </div>
      </div>
      <div class="md-stat-card">
        <span class="md-stat-card__icon md-stat-card__icon--violet"><MdIcon name="file" :size="19" /></span>
        <div class="md-stat-card__body">
          <div class="md-stat-card__label">文档总数</div>
          <div class="md-stat-card__value">{{ stats?.docTotal ?? 0 }}</div>
          <div class="md-stat-card__hint">本月新增 {{ stats?.docTotalDelta ?? 0 }}</div>
        </div>
      </div>
      <div class="md-stat-card">
        <span class="md-stat-card__icon md-stat-card__icon--amber"><MdIcon name="eye" :size="19" /></span>
        <div class="md-stat-card__body">
          <div class="md-stat-card__label">
            对外开放库中的文档
            <HelpTip text="可见范围为「所有登录用户」的库，其文档总数。" />
          </div>
          <div class="md-stat-card__value">{{ stats?.publicDocTotal ?? 0 }}</div>
          <div class="md-stat-card__hint">所有登录用户可见</div>
        </div>
      </div>
    </div>

    <div class="md-overview-layout">
      <section class="md-card-panel">
        <div class="md-panel-head">
          <div class="md-panel-head__text">
            <h2>最近更新</h2>
            <p class="md-panel-desc">按最后修改时间排序，最多显示 6 个知识库。</p>
          </div>
          <span class="md-spacer" />
          <el-button size="small" @click="router.push({ name: 'kbs', params: { org } })">
            <MdIcon name="books" :size="14" />查看全部
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
              <div class="md-recent-item__thumb">
                <MdIcon name="books" :size="20" />
                <img v-if="coverSrc(kb)" :src="coverSrc(kb)" alt="" loading="lazy" @error="hideBrokenImage" />
              </div>
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

      <aside class="md-card-panel">
        <div class="md-panel-head">
          <div class="md-panel-head__text">
            <h2>当前账号</h2>
            <p class="md-panel-desc">这次登录使用的身份。</p>
          </div>
        </div>
        <div class="md-panel-body">
          <div class="md-profile">
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
                <span>知识库</span>
                <span>{{ stats?.kbTotal ?? 0 }} 个</span>
              </div>
            </div>
            <el-button class="md-fill" @click="router.push('/account')">
              <MdIcon name="user" :size="14" />账号设置
            </el-button>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>
