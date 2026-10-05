<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import MdIcon from '@/admin/components/MdIcon.vue'
import { useAuthStore } from '@/admin/stores/auth'
import { useSiteStore } from '@/shared/stores/site'
import { appHref } from '@/shared/appBase'

/**
 * 登录页。
 *
 * <p>桌面端是「左品牌 / 右表单」两栏：一栏说清这是什么产品、进来能做什么（登录页也是访客对
 * 平台的第一印象），一栏只管把账密填完。两栏在同一张圆角卡里，视觉上是一块东西而不是两个区域。
 *窄屏（≤900px）左栏收成一条紧凑品牌头，只留 Logo 与站名，表单独占一栏。</p>
 *
 * <p>注册页（{@code RegisterView}）用同一个外壳，只是换左栏文案与表单字段，视觉保持一致。</p>
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const site = useSiteStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    // 默认落在 /console：组织段不在地址栏里就没法进任何管理页，交给落地页去解析该进哪个组织
    const redirect = (route.query.redirect as string) || '/console'
    router.replace(redirect)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="md-login">
    <div class="md-login__shell">
      <!-- 左栏：产品说明。窄屏下只留顶部一条品牌头，要点列表整块收起 -->
      <section class="md-login__intro">
        <div class="md-login__intro-top">
          <span class="md-login__logo" :class="{ 'is-image': site.value.logoSrc }">
            <img v-if="site.value.logoSrc" :src="site.value.logoSrc" alt="" />
            <template v-else>{{ site.value.name.slice(0, 1) }}</template>
          </span>
          <div class="md-login__intro-name">
            <strong>{{ site.value.name }}</strong>
            <small>{{ site.value.subtitle || '轻量化 · 高性能的知识库平台' }}</small>
          </div>
        </div>

        <div class="md-login__intro-body">
          <h1>把知识写下来，<br>把文档送出去</h1>
          <p>Markdown 写作、实时预览、组织与权限、对外分享，一套系统走完。</p>
          <ul class="md-login__points">
            <li>
              <span class="md-login__point-icon"><MdIcon name="edit" :size="15" /></span>
              <div><strong>写作即所得</strong><small>Markdown 编辑与阅读两套渲染，表格、代码、公式、图表都齐</small></div>
            </li>
            <li>
              <span class="md-login__point-icon"><MdIcon name="users" :size="15" /></span>
              <div><strong>组织与权限</strong><small>成员角色、库级可见性、操作审计，各管一段</small></div>
            </li>
            <li>
              <span class="md-login__point-icon"><MdIcon name="share" :size="15" /></span>
              <div><strong>分享免登录</strong><small>外链即读，可设口令与有效期，浏览量可见</small></div>
            </li>
          </ul>
        </div>

        <p class="md-login__intro-foot">单 SPA · 前后端分离 · 数据留在自己的服务器</p>
      </section>

      <!-- 右栏：表单 -->
      <section class="md-login__panel">
        <header class="md-login__head">
          <h2>欢迎回来</h2>
          <p>登录后进入管理工作台</p>
        </header>

        <el-form label-position="top" class="md-login__form" @submit.prevent="submit">
          <el-form-item label="用户名 / 邮箱">
            <el-input v-model="form.username" size="large" placeholder="admin" autocomplete="username">
              <template #prefix><MdIcon name="user" :size="15" /></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              show-password
              placeholder="请输入密码"
              autocomplete="current-password"
              @keyup.enter="submit"
            >
              <template #prefix><MdIcon name="lock" :size="15" /></template>
            </el-input>
          </el-form-item>
          <el-button type="primary" size="large" class="md-login__submit" :loading="loading" @click="submit">
            <MdIcon name="login" :size="15" />登录
          </el-button>
        </el-form>

        <div class="md-login__links">
          <span>还没有账号？<router-link to="/register">注册一个</router-link></span>
          <a :href="appHref('/')">← 返回门户首页</a>
        </div>
      </section>
    </div>
  </div>
</template>
