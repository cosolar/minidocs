<script setup lang="ts">
/**
 * 后台页面标题区：眉题、主标题、一句说明，右侧放本页主操作。
 *
 * <p>统一放在每个页面的最上方，顶栏那一行只负责「我在哪个组织」与全局动作；
 * 页面自己的层级、说明和主按钮都在这里，避免每页各写一套间距。</p>
 */
defineProps<{
  title: string
  description?: string
  eyebrow?: string
}>()
</script>

<template>
  <header class="md-page-head">
    <div class="md-page-head__text">
      <p v-if="eyebrow" class="md-page-head__eyebrow">{{ eyebrow }}</p>
      <h1 class="md-page-head__title">{{ title }}</h1>
      <p v-if="description" class="md-page-head__desc">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="md-page-head__actions">
      <slot name="actions" />
    </div>
  </header>
</template>
