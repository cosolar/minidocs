<script setup lang="ts">
import Icon from '@/user/components/Icon.vue'
import { useSiteStore } from '@/shared/stores/site'

const site = useSiteStore()
/** 年份只在这里取运行时值：避免写死后逐年过期 */
const year = new Date().getFullYear()

/** 四项能力：一句话说清这套系统交付什么，不做站外链接 */
const FEATURES = [
  { icon: 'bolt', title: '极致性能', desc: '极速加载体验' },
  { icon: 'shield', title: '权限可控', desc: '多层级权限管理' },
  { icon: 'code', title: '开源开放', desc: 'Apache-2.0 协议' },
  { icon: 'heart', title: '与您同行', desc: '共建更好的知识平台' }
] as const
</script>

<template>
  <!--
    页脚：左侧品牌 + 四项能力 + 居中版权，浅彩山水压在底下。
    门户内容通栏排版，页脚同样只吃左右页边距。
  -->
  <footer class="md-footer">
    <span class="md-footer__bg" aria-hidden="true" />
    <div class="md-footer__inner">
      <div class="md-footer__grid">
        <div class="md-footer__brand">
          <div class="md-footer__brand-row">
            <span class="md-footer__logo is-image" aria-hidden="true">
              <img :src="site.brandSrc" alt="" />
            </span>
            <span v-if="!site.logoOnly" class="md-footer__text">
              <strong>{{ site.value.name }}</strong>
              <small v-if="site.value.subtitle">{{ site.value.subtitle }}</small>
            </span>
          </div>
          <p class="md-footer__desc">
            轻量、可私有部署的 Markdown 知识库平台，写作、阅读、分享与权限一条线。
          </p>
        </div>

        <ul class="md-footer__features">
          <li v-for="feature in FEATURES" :key="feature.title" class="md-footer__feature">
            <span class="md-footer__feature-icon"><Icon :name="feature.icon" :size="18" /></span>
            <strong>{{ feature.title }}</strong>
            <small>{{ feature.desc }}</small>
          </li>
        </ul>
      </div>

      <div class="md-footer__bottom">
        <p class="md-footer__copy">© {{ year }} {{ site.value.name }} · 开源自部署 · 让知识创造更多可能</p>
      </div>
    </div>
  </footer>
</template>
