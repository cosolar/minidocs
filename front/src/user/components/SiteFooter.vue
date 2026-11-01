<script setup lang="ts">
import { useSiteStore } from '@/shared/stores/site'
import { DEFAULT_LOGO } from '@/shared/brand'

const site = useSiteStore()
/** 年份只在这里取运行时值：避免写死后逐年过期 */
const year = new Date().getFullYear()

/** 仓库地址对一个部署来说只有一个，硬编码一处；页脚三栏的站外链接都从它派生 */
const REPO = 'https://github.com/cosolar/minidocs'

const columns = [
  {
    key: 'product',
    title: '产品',
    links: [
      { label: '知识库广场', to: '/' },
      { label: '在线演示', href: 'https://docs.minims.cn' }
    ]
  },
  {
    key: 'help',
    title: '帮助',
    links: [
      { label: '快速开始', href: `${REPO}/blob/main/README.md` },
      { label: '部署指南', href: `${REPO}/blob/main/docs/DEPLOYMENT.md` },
      { label: '阅读主题', href: `${REPO}/blob/main/docs/THEMES.md` }
    ]
  },
  {
    key: 'open',
    title: '开源',
    links: [
      { label: 'GitHub 仓库', href: REPO },
      { label: '问题反馈', href: `${REPO}/issues` },
      { label: 'Apache-2.0 协议', href: `${REPO}/blob/main/LICENSE` }
    ]
  }
]
</script>

<template>
  <!--
    页脚：品牌大块 + 三栏链接 + 版权行，取意「首页收尾即目录」。

    <p>品牌栏的描述与徽章回答「这是什么、能不能白嫖」，三栏把访客最常去的站外入口
    （演示、文档、仓库）一次摆齐；山水背景在这段收成近景 —— 底部垫一层渐变，
    多栏小字压在上面才读得清。</p>
  -->
  <footer class="md-footer">
    <div class="md-footer__inner">
      <div class="md-footer__grid">
        <div class="md-footer__brand">
          <div class="md-footer__brand-row">
            <span class="md-footer__logo is-image" aria-hidden="true">
              <img :src="site.value.logoSrc || DEFAULT_LOGO" alt="" />
            </span>
            <span v-if="!site.logoOnly" class="md-footer__text">
              <strong>{{ site.value.name }}</strong>
              <small v-if="site.value.subtitle">{{ site.value.subtitle }}</small>
            </span>
          </div>
          <p class="md-footer__desc">
            轻量、可私有部署的 Markdown 知识库平台 —— 写作、阅读、分享、权限一条线，数据留在自己的服务器。
          </p>
          <div class="md-footer__badges">
            <span class="md-footer__badge">Apache-2.0</span>
            <span class="md-footer__badge">可自部署</span>
          </div>
        </div>

        <nav v-for="col in columns" :key="col.key" class="md-footer__col" :aria-label="col.title">
          <h3>{{ col.title }}</h3>
          <template v-for="link in col.links" :key="link.label">
            <RouterLink v-if="link.to" :to="link.to">{{ link.label }}</RouterLink>
            <a v-else :href="link.href" target="_blank" rel="noopener noreferrer">{{ link.label }}</a>
          </template>
        </nav>
      </div>

      <div class="md-footer__bottom">
        <p class="md-footer__copy">© {{ year }} {{ site.value.name }} · 让知识管理更高效</p>
        <span class="md-footer__note">开源自部署 · 数据由你掌控</span>
      </div>
    </div>
  </footer>
</template>
