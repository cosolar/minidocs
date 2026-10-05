<script setup lang="ts">
import Icon from '@/user/components/Icon.vue'
import { useSiteStore } from '@/shared/stores/site'

const site = useSiteStore()
/** 页脚只在门户首页出现，年份取运行时，避免写死后逐年过期 */
const year = new Date().getFullYear()
</script>

<template>
  <!--
    落款：徽标 + 站名/副标题 → 渐隐细线 → 版权，三段居中收束。
    文字与徽标都压在 .md-portal .md-footer::before 的点阵纹理之上，z-index 提一层避免被盖住。
  -->
  <footer class="md-footer">
    <div class="md-footer__brand">
      <span class="md-footer__logo" :class="{ 'is-image': site.value.logoSrc }" aria-hidden="true">
        <img v-if="site.value.logoSrc" :src="site.value.logoSrc" alt="" />
        <Icon v-else name="books" :size="16" />
      </span>
      <span class="md-footer__text">
        <strong>{{ site.value.name }}</strong>
        <small v-if="site.value.subtitle">{{ site.value.subtitle }}</small>
      </span>
    </div>

    <span class="md-footer__rule" aria-hidden="true"></span>

    <p class="md-footer__copy">
      <span>© {{ year }} {{ site.value.name }}</span>
      <span class="md-footer__dot" aria-hidden="true"></span>
      <span>沉淀知识，构建体系</span>
    </p>
  </footer>
</template>
