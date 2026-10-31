<script setup lang="ts">
import Icon from '@/user/components/Icon.vue'
import { useSiteStore } from '@/shared/stores/site'
import { DEFAULT_LOGO } from '@/shared/brand'

const site = useSiteStore()
/** 年份只在这里取运行时值：避免写死后逐年过期 */
const year = new Date().getFullYear()
</script>

<template>
  <!--
    页脚：整幅山水铺底 + 居中三段式。

    <p>背景图不是装饰，是这一页的「远景」—— 卡片列表一路向下读到底，页脚用一张
    有纵深的图收尾，页面才有头有尾。所以它满宽出血，而不是缩在内容列里。</p>

    <p>文字与标志都压在图上，所以各自的反白处理不同：标志是纯色块，直接压图即可；
    <p>文字与标志都压在图上，所以各自的反白处理不同：标志是纯色块，直接压图即可；
  -->
  <footer class="md-footer">
    <div class="md-footer__inner">
      <div class="md-footer__brand">
        <span class="md-footer__logo is-image" :class="{ 'is-builtin': !site.value.logoSrc }" aria-hidden="true">
          <img :src="site.value.logoSrc || DEFAULT_LOGO" alt="" />
        </span>
        <span class="md-footer__text">
          <strong>{{ site.value.name }}</strong>
          <small v-if="site.value.subtitle">{{ site.value.subtitle }}</small>
        </span>
      </div>

      <!--
        源码入口与版权并成一行：设计图里社交图标就在版权那一行的左侧，单独占一排
        会把页脚高度多撑出三十多像素 —— 而这一屏本来只需要「现在在哪」和「这是谁的」
        两件事，没必要为它花第三行。

        地址与侧栏页脚那行版本标识是同一个仓库，所以同样硬编码，不给后台再添一个
        配置项 —— 仓库地址对一个部署来说只有一个，两处各写一遍反而容易只改一处。
      -->
      <div class="md-footer__row">
        <nav class="md-footer__social" aria-label="源码与联系">
          <a
            class="md-footer__social-btn"
            href="https://github.com/cosolar/minidocs.git"
            target="_blank"
            rel="noopener noreferrer"
            title="GitHub 仓库"
            aria-label="GitHub 仓库"
          >
            <Icon name="github" :size="16" />
          </a>
        </nav>

        <p class="md-footer__copy">
          <span>© {{ year }} {{ site.value.name }}</span>
          <span class="md-footer__dot" aria-hidden="true"></span>
          <span>让知识管理更高效</span>
        </p>
      </div>
    </div>
  </footer>
</template>
