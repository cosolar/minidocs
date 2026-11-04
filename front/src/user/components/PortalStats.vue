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
 * 门户统计：一排贴着区块标题的数字，不是五张卡。
 *
 * <p>门户首页的视觉重心是 Hero 的主张与搜索，统计只回答「这里有多少东西」。
 * 标题右侧一行「12 个知识库 · 340 篇文档 · 5,210 访问」就够了；
 * 做成卡片会与下面的知识库卡抢层级。</p>
 *
 * <p>口径不变：分享等同于发布，共享 = 分享未加密，加密 = 分享已加密（需口令）。
 * 访问量取发布分享的累计 views，「本月」按 share_view_log 本月行数。这里不出现
 * 「本组织」，也不叫「公开 / 私有」——那是后台「可见范围」的用词，与「门户上要不要
 * 口令」正交。</p>
 */
const fmt = (n: number) => n.toLocaleString('zh-CN')

const items = computed(() => {
  const s = props.stats
  return [
    {
      key: 'kb',
      label: '知识库',
      icon: 'books',
      value: fmt(s.kbTotal),
      hint: s.kbTotalDelta > 0 ? `本月发布 ${s.kbTotalDelta}` : ''
    },
    {
      key: 'public',
      label: '共享',
      icon: 'share',
      value: fmt(s.kbPublic),
      hint: s.kbTotal > 0 ? `占比 ${publicPercent.value}%` : ''
    },
    {
      key: 'private',
      label: '加密',
      icon: 'lock',
      value: fmt(s.kbPrivate),
      hint: '需访问密码'
    },
    {
      key: 'docs',
      label: '文档',
      icon: 'file',
      value: fmt(s.docTotal),
      hint: s.docTotalDelta > 0 ? `本月新增 ${s.docTotalDelta}` : ''
    },
    {
      key: 'views',
      label: '访问量',
      icon: 'eye',
      value: fmt(s.viewTotal),
      hint: s.viewDelta > 0 ? `本月 ${s.viewDelta}` : ''
    }
  ]
})
</script>

<template>
  <section class="md-statsline" aria-label="统计概览">
    <span
      v-for="item in items"
      :key="item.key"
      class="md-stat"
      :class="`is-${item.key}`"
      :title="item.hint || undefined"
      :aria-label="`${item.label} ${item.value}${item.hint ? '，' + item.hint : ''}`"
    >
      <span class="md-stat__icon" aria-hidden="true"><Icon :name="item.icon" :size="13" /></span>
      <b>{{ item.value }}</b>
      <span class="md-stat__label">{{ item.label }}</span>
    </span>
  </section>
</template>
