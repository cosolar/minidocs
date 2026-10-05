<script setup lang="ts">
import Icon from '@/user/components/Icon.vue'

withDefaults(
  defineProps<{
    code: string
    title: string
    message?: string
    /** 是否展示「返回门户首页」按钮 */
    showHome?: boolean
  }>(),
  { message: '', showHome: true }
)
</script>

<template>
  <div class="md-state">
    <main class="md-error">
      <span class="md-error__code">{{ code }}</span>
      <h1>{{ title }}</h1>
      <p>{{ message }}</p>
      <router-link v-if="showHome" class="md-btn md-btn--primary" to="/">
        <Icon name="home" :size="14" />返回门户首页
      </router-link>
    </main>
  </div>
</template>
