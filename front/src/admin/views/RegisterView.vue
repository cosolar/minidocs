<script setup lang="ts">
/**
 * 自助注册（路由 {@code /register}，规范 §3.1 / F1）。
 *
 * <p>注册成功只意味着账号存在了：后端**不回 token**，个人组织虽然已经建好，会话还没建立，
 * 所以这里紧接着用同一组凭据走一次登录。少这一步，用户就会停在一个「刚注册却没有登录态」
 * 的页面上，点任何管理入口都被弹回登录页。</p>
 *
 * <p>自主注册可以被 {@code minidocs.register-enabled} 关掉。前端不预判这个开关 —— 开关的真值
 * 在后端，页面预判只会多出一份会过期的副本；关掉时后端的 403 文案直接显示出来即可。</p>
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/admin/api'
import { toApiErrorInfo } from '@/shared/api/http'
import { appHref } from '@/shared/appBase'
import MdIcon from '@/admin/components/MdIcon.vue'
import { useAuthStore } from '@/admin/stores/auth'
import { useSiteStore } from '@/shared/stores/site'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const site = useSiteStore()

const form = reactive({ username: '', displayName: '', email: '', password: '', confirm: '' })
const loading = ref(false)

async function submit() {
  const username = form.username.trim()
  if (!/^[A-Za-z0-9_]{3,32}$/.test(username)) {
    ElMessage.warning('用户名需为 3-32 位字母、数字或下划线')
    return
  }
  if (form.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  if (form.password !== form.confirm) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    await authApi.register({
      username,
      password: form.password,
      displayName: form.displayName.trim() || undefined,
      email: form.email.trim() || undefined
    })
    // 注册接口不给会话，紧接着登录一次；失败也不把账号回滚，提示去登录即可
    try {
      await auth.login(username, form.password)
      ElMessage.success('注册完成，个人组织已经建好')
      router.replace((route.query.redirect as string) || '/console')
      return
    } catch {
      ElMessage.success('注册完成，请登录')
    }
  } catch (error) {
    // register 带 silentError，全局 onError 不再兜底，所以「用户名已被占用」「自助注册已关闭」
    // 这些文案必须在这里说出口 —— 否则用户点完注册什么反应都没有，只能反复试。
    ElMessage.error(toApiErrorInfo(error).message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="md-login">
    <!-- 与登录页同一个外壳：左栏讲产品，右栏只管填表（字段多，右栏留白也更经得起） -->
    <div class="md-login__shell">
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
          <h1>注册即可开始，<br>不必等管理员</h1>
          <p>系统会自动为你建一个个人组织，第一篇文档可以直接写。</p>
          <ul class="md-login__points">
            <li>
              <span class="md-login__point-icon"><MdIcon name="user-plus" :size="15" /></span>
              <div><strong>个人组织</strong><small>注册即建，只有你一个人；需要协作再邀请成员</small></div>
            </li>
            <li>
              <span class="md-login__point-icon"><MdIcon name="books" :size="15" /></span>
              <div><strong>多知识库</strong><small>按主题分库，库级可见性与分享各自独立</small></div>
            </li>
            <li>
              <span class="md-login__point-icon"><MdIcon name="share" :size="15" /></span>
              <div><strong>随时对外分享</strong><small>生成外链即可阅读，可设口令与有效期</small></div>
            </li>
          </ul>
        </div>

        <p class="md-login__intro-foot">单 SPA · 前后端分离 · 数据留在自己的服务器</p>
      </section>

      <section class="md-login__panel">
        <header class="md-login__head">
          <h2>创建账号</h2>
          <p>注册后会自动进入你的第一个知识库</p>
        </header>

        <el-form label-position="top" class="md-login__form" @submit.prevent="submit">
          <el-form-item label="用户名（3-32 位，字母 / 数字 / 下划线，全局唯一）">
            <el-input v-model="form.username" size="large" placeholder="alice" autocomplete="username" maxlength="32">
              <template #prefix><MdIcon name="user" :size="15" /></template>
            </el-input>
          </el-form-item>
          <el-form-item label="显示名（选填）">
            <el-input v-model="form.displayName" size="large" maxlength="64">
              <template #prefix><MdIcon name="user" :size="15" /></template>
            </el-input>
          </el-form-item>
          <el-form-item label="邮箱（选填，用于找回）">
            <el-input v-model="form.email" size="large" maxlength="128" autocomplete="email">
              <template #prefix><MdIcon name="globe" :size="15" /></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码（至少 6 位）">
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              show-password
              autocomplete="new-password"
            >
              <template #prefix><MdIcon name="lock" :size="15" /></template>
            </el-input>
          </el-form-item>
          <el-form-item label="确认密码">
            <el-input
              v-model="form.confirm"
              type="password"
              size="large"
              show-password
              autocomplete="new-password"
              @keyup.enter="submit"
            >
              <template #prefix><MdIcon name="lock" :size="15" /></template>
            </el-input>
          </el-form-item>
          <el-button type="primary" size="large" class="md-login__submit" :loading="loading" @click="submit">
            <MdIcon name="user-plus" :size="15" />注册并进入
          </el-button>
        </el-form>

        <div class="md-login__links">
          <span>已有账号？<router-link to="/login">去登录</router-link></span>
          <a :href="appHref('/')">← 返回门户首页</a>
        </div>
      </section>
    </div>
  </div>
</template>
