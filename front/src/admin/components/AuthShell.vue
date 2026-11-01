<script setup lang="ts">
/**
 * 登录 / 注册共用的外壳：左侧品牌与卖点，右侧表单。
 *
 * <p>两页只换文案与表单，外壳保持一致 —— 登录页是访客对产品的第一印象，
 * 注册页紧接着出现时不该像换了一个站点。</p>
 */
import MdIcon from '@/admin/components/MdIcon.vue'
import { useSiteStore } from '@/shared/stores/site'
import portalBg from '@/user/assets/portal-bg.png'

defineProps<{ points: { icon: string; strong: string; small: string }[] }>()

const site = useSiteStore()
</script>

<template>
  <div class="md-login">
    <div class="md-login__shell">
      <section class="md-login__intro" :style="{ '--md-auth-art': `url(${portalBg})` }">
        <div class="md-login__intro-top">
          <span class="md-login__logo is-image" :style="site.logoStyle">
            <img :src="site.brandSrc" alt="" />
          </span>
          <div v-if="!site.logoOnly" class="md-login__intro-name">
            <strong>{{ site.value.name }}</strong>
            <small>{{ site.value.subtitle || '轻量 · 高性能的知识库平台' }}</small>
          </div>
        </div>

        <div class="md-login__art" aria-hidden="true">
          <div class="md-login__demo">
            <div class="md-login__demo-win">
              <div class="md-login__demo-bar">
                <span class="md-login__demo-dots"><i /><i /><i /></span>
                <span class="md-login__demo-search"><MdIcon name="search" :size="11" />搜索文档</span>
                <span class="md-login__demo-user">JI</span>
              </div>
              <div class="md-login__demo-body">
                <aside class="md-login__demo-side">
                  <span class="is-active"><MdIcon name="books" :size="13" />知识库</span>
                  <span><MdIcon name="folder" :size="13" />目录</span>
                  <span><MdIcon name="users" :size="13" />成员</span>
                  <span><MdIcon name="share" :size="13" />分享</span>
                  <div class="md-login__demo-me">
                    <i>JI</i>
                    <span><strong>极简团队</strong><small>私有空间</small></span>
                  </div>
                </aside>
                <div class="md-login__demo-main">
                  <div class="md-login__demo-head"><strong>全部知识库</strong><span>3 个</span></div>
                  <div class="md-login__demo-cards">
                    <div class="md-login__demo-card">
                      <span class="md-login__demo-cover is-a" />
                      <strong>产品使用指南</strong>
                      <small>12 篇文档</small>
                    </div>
                    <div class="md-login__demo-card">
                      <span class="md-login__demo-cover is-b" />
                      <strong>部署与运维</strong>
                      <small>8 篇文档</small>
                    </div>
                  </div>
                  <div class="md-login__demo-row"><MdIcon name="file" :size="12" /><span>快速开始</span><em>刚刚</em></div>
                  <div class="md-login__demo-row"><MdIcon name="file" :size="12" /><span>权限模型</span><em>昨天</em></div>
                  <div class="md-login__demo-row"><MdIcon name="file" :size="12" /><span>团队手册</span><em>3 天前</em></div>
                  <div class="md-login__demo-row"><MdIcon name="file" :size="12" /><span>接口鉴权</span><em>1 周前</em></div>
                  <div class="md-login__demo-foot">
                    <span class="md-login__demo-avatars"><i>Z</i><i>L</i><i>W</i></span>
                    <span>3 位成员共同维护</span>
                  </div>
                </div>
              </div>
            </div>
            <span class="md-login__demo-chip is-a"><MdIcon name="edit" :size="12" />Markdown</span>
            <span class="md-login__demo-chip is-b"><MdIcon name="search" :size="12" />全文检索</span>
            <span class="md-login__demo-chip is-c"><MdIcon name="share" :size="12" />分享链接</span>
            <span class="md-login__demo-chip is-d"><MdIcon name="lock" :size="12" />权限控制</span>
          </div>
        </div>

        <div class="md-login__intro-body">
          <h1><slot name="headline" /></h1>
          <p><slot name="lead" /></p>
          <ul class="md-login__points">
            <li v-for="point in points" :key="point.strong">
              <span class="md-login__point-icon"><MdIcon :name="point.icon" :size="16" /></span>
              <div><strong>{{ point.strong }}</strong><small>{{ point.small }}</small></div>
            </li>
          </ul>
        </div>

        <p class="md-login__intro-foot">单 SPA · 前后端分离 · 数据留在自己的服务器</p>
      </section>

      <section class="md-login__panel"><slot /></section>
    </div>
  </div>
</template>
