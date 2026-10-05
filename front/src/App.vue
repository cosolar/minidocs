<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import TopBar from '@/user/components/TopBar.vue'
import SearchPanel from '@/user/components/SearchPanel.vue'

const route = useRoute()

/** 门户与阅读页有顶栏与搜索面板；分享页、错误页与管理端页面没有 */
const chrome = computed(() => route.meta.reader === true)
</script>

<template>
  <TopBar v-if="chrome" />
  <!--
    全站唯一的滚动区。id 是给路由的 scrollBehavior 与滚动联动（scrollspy / 记住返回位置）用的，
    它们都拿这个元素当滚动容器，不再用 window。
  -->
  <div id="md-view" class="md-view">
    <router-view />
  </div>
  <SearchPanel v-if="chrome" />
</template>
