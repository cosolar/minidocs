<script setup lang="ts">
import { computed } from 'vue'
import StatusView from '@/user/views/StatusView.vue'
import { useBodyClasses } from '@/user/composables/useBodyClasses'

useBodyClasses(computed(() => ['md-page--error']))
</script>

<template>
  <StatusView code="404" title="页面不存在" message="你访问的地址不存在或已被移除。" />
</template>
