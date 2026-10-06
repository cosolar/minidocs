<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { kbApi } from '@/admin/api'
import MdIcon from '@/admin/components/MdIcon.vue'
import type { KbVO, StatsVO } from '@/shared/api/types'
import { portalKbUrl } from '@/shared/portal'
import { appHref } from '@/shared/appBase'
import { visibilityHint, visibilityLabel, visibilityChipClass } from '@/shared/visibility'
import { useAuthStore } from '@/admin/stores/auth'
import { useIsMobile } from '@/admin/composables/useIsMobile'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 窄屏下「当前账号」的描述列表要退回单列，两列会把值挤成一行两个字 */
const isMobile = useIsMobile()

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

function openWorkspace(kb: KbVO) {
  router.push({ name: 'kb-workspace', params: { org, slug: kb.slug } })
}

function openPortal(slug: string) {
  window.open(appHref(portalKbUrl(org, slug)), '_blank')
}
</script>

<template>
  <div v-loading="loading">
    <div class="md-ad-stat-grid">
      <div class="md-stat-card">
        <div class="md-stat-card__label">知识库总数</div>
        <div class="md-stat-card__value">{{ stats?.kbTotal ?? 0 }}</div>
        <div class="md-stat-card__hint">本月新增 {{ stats?.kbTotalDelta ?? 0 }}</div>
      </div>
      <div class="md-stat-card">
        <div class="md-stat-card__label">可见范围分布</div>
        <div class="md-stat-card__value">
          {{ stats?.kbPublic ?? 0 }} <span style="font-size: 16px; color: var(--md-text-3)">/</span>
          {{ stats?.kbOrg ?? 0 }} <span style="font-size: 16px; color: var(--md-text-3)">/</span>
          {{ stats?.kbPrivate ?? 0 }}
        </div>
        <div class="md-stat-card__hint">所有登录用户 / 本组织成员 / 仅维护名单，与门户发布无关</div>
      </div>
      <div class="md-stat-card">
        <div class="md-stat-card__label">文档总数</div>
        <div class="md-stat-card__value">{{ stats?.docTotal ?? 0 }}</div>
        <div class="md-stat-card__hint">本月新增 {{ stats?.docTotalDelta ?? 0 }}</div>
      </div>
      <div class="md-stat-card">
        <div class="md-stat-card__label">对外开放库中的文档</div>
        <div class="md-stat-card__value">{{ stats?.publicDocTotal ?? 0 }}</div>
        <div class="md-stat-card__hint">可见范围为「所有登录用户」的库，其文档总数</div>
      </div>
    </div>

    <div class="md-card-panel md-mt-16">
      <div class="md-panel-head">
        <h2>最近更新</h2>
        <span class="md-spacer" />
        <el-button size="small" @click="router.push({ name: 'kbs', params: { org } })">
          <MdIcon name="books" :size="14" />全部知识库
        </el-button>
      </div>
      <div class="md-panel-body">
        <el-empty v-if="!loading && recent.length === 0" description="还没有知识库，去创建第一个吧" />
        <div v-else class="md-kb-grid">
          <div v-for="kb in recent" :key="kb.id" class="md-kb-card">
            <div class="md-kb-card__cover">
              <img v-if="coverSrc(kb)" :src="coverSrc(kb)" :alt="kb.name" loading="lazy" />
            </div>
            <div class="md-kb-card__body">
              <div class="md-kb-card__title">
                <span>{{ kb.name }}</span>
                <span class="md-ad-chip" :class="visibilityChipClass(kb.visibility)" :title="visibilityHint(kb.visibility)">
                  {{ visibilityLabel(kb.visibility) }}
                </span>
              </div>
              <p class="md-kb-card__desc">{{ kb.description || '暂无描述' }}</p>
              <div class="md-kb-card__meta">
                <span>{{ kb.docCount }} 篇</span>
                <span>{{ kb.updatedText }}</span>
              </div>
            </div>
            <div class="md-kb-card__actions">
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
    </div>

    <div class="md-card-panel md-mt-16">
      <div class="md-panel-head"><h2>当前账号</h2></div>
      <div class="md-panel-body">
        <el-descriptions :column="isMobile ? 1 : 2" border>
          <el-descriptions-item label="用户名">{{ auth.user?.username }}</el-descriptions-item>
          <el-descriptions-item label="显示名">{{ auth.user?.displayName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ auth.user?.email || '—' }}</el-descriptions-item>
          <el-descriptions-item label="平台角色">
            <el-tag size="small" :type="auth.isPlatformAdmin ? 'danger' : 'info'">
              {{ auth.isPlatformAdmin ? '管理员' : '普通用户' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </div>
  </div>
</template>
