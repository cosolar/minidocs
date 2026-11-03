<script setup lang="ts">
/**
 * 后台页面标题区：眉题、主标题、一句说明，右侧放本页主操作。
 *
 * <p>统一放在每个页面的最上方，顶栏那一行只负责「我在哪个组织」与全局动作；
 * 页面自己的层级、说明和主按钮都在这里，避免每页各写一套间距。</p>
 *
 * <p>{@code hero} 是列表页的水彩头部：整页第一屏用品牌插画托住标题与主按钮，
 * 筛选条再压着它的下沿浮起。只给信息型列表页用，表单页保持轻量的普通页头。</p>
 */
import MdIcon from './MdIcon.vue'
import heroBg from '@/admin/assets/hero-bg.png'

withDefaults(
  defineProps<{
    title: string
    description?: string
    eyebrow?: string
    icon?: string
    hero?: boolean
  }>(),
  { icon: 'books', hero: false }
)
</script>

<template>
  <header
    class="md-page-head"
    :class="{ 'md-page-head--hero': hero }"
    :style="hero ? { '--md-hero-art': `url(${heroBg})` } : undefined"
  >
    <span v-if="hero" class="md-page-head__icon" aria-hidden="true">
      <MdIcon :name="icon" :size="28" />
    </span>
    <div class="md-page-head__text">
      <p v-if="eyebrow && !hero" class="md-page-head__eyebrow">{{ eyebrow }}</p>
      <h1 class="md-page-head__title">{{ title }}</h1>
      <p v-if="description" class="md-page-head__desc">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="md-page-head__actions">
      <slot name="actions" />
    </div>
  </header>
</template>
