<script setup lang="ts">
import { computed } from 'vue'
import Icon from '@/user/components/Icon.vue'
import type { PortalStatsVO } from '@/user/api/types'

const props = defineProps<{ stats: PortalStatsVO }>()

const publicPercent = computed(() => {
  const s = props.stats
  if (!s.kbTotal) return 0
  return Math.round((s.kbPublic * 100) / s.kbTotal)
})

/**
 * 五张卡片：总数 → 已发布的两档（共享 / 加密）→ 文档量 → 访问量。
 *
 * <p>分享等同于发布，门户统计的是已发布库；共享 = 分享未加密，加密 = 分享已加密（需口令）。
 * 访问量取的是发布分享的累计 views（分享页与门户阅读命中合并），「本月」按 share_view_log
 * 本月行数（≈ 本月独立访客数）。这里不出现「本组织」，也不叫「公开 / 私有」——
 * 那是后台「可见范围」那一层的用词，与「门户上要不要口令」正交，混用会把两件事说成一件事。</p>
 */
const items = computed(() => {
  const s = props.stats
  return [
    {
      key: 'kb',
      icon: 'books' as const,
      tone: 'blue',
      label: '知识库总数',
      value: s.kbTotal,
      hint: s.kbTotalDelta > 0 ? `本月发布 ${s.kbTotalDelta}` : '',
      trend: s.kbTotalDelta > 0
    },
    {
      key: 'public',
      icon: 'globe' as const,
      tone: 'green',
      label: '共享',
      value: s.kbPublic,
      hint: s.kbTotal > 0 ? `占比 ${publicPercent.value}%` : '',
      trend: false
    },
    {
      key: 'private',
      icon: 'lock' as const,
      tone: 'amber',
      label: '加密',
      value: s.kbPrivate,
      hint: '读者需输入访问密码',
      trend: false
    },
    {
      key: 'docs',
      icon: 'fileText' as const,
      tone: 'green',
      label: '文档总数',
      value: s.docTotal,
      hint: s.docTotalDelta > 0 ? `本月新增 ${s.docTotalDelta}` : '',
      trend: s.docTotalDelta > 0
    },
    {
      key: 'views',
      icon: 'eye' as const,
      tone: 'violet',
      label: '访问量',
      value: s.viewTotal,
      hint: s.viewDelta > 0 ? `本月 ${s.viewDelta}` : '',
      trend: s.viewDelta > 0
    }
  ]
})
</script>

<template>
  <section class="md-stat-grid" aria-label="统计概览">
    <article v-for="item in items" :key="item.key" class="md-stat" :class="`is-${item.tone}`">
      <span class="md-stat__icon">
        <Icon :name="item.icon" :size="20" />
      </span>
      <div class="md-stat__text">
        <span class="md-stat__label">{{ item.label }}</span>
        <strong class="md-stat__value">{{ item.value }}</strong>
        <small v-if="item.hint" class="md-stat__hint" :class="{ 'is-trend': item.trend }">{{ item.hint }}</small>
      </div>
    </article>
  </section>
</template>
