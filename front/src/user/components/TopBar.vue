<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '@/user/components/Icon.vue'
import { useSessionStore } from '@/user/stores/session'
import { useSearchPanel } from '@/user/composables/useSearchPanel'
import { useSiteStore } from '@/shared/stores/site'
import { appHref } from '@/shared/appBase'

/**
 * 门户与阅读页顶栏。
 *
 * <p>这一条只放三样东西：品牌（回首页）、全站搜索、身份。动作类入口（新建知识库、进管理后台、
 * 改组织设置）不放在这里——它们要么属于某个具体页面（门户首页的筛选栏、知识库管理页的表头），
 * 要么收进身份菜单。顶栏是每页都在的全局件，往里加按钮等于给每个页面都加一份噪音。</p>
 *
 * <p>不引 Element Plus：规范 F11 要求阅读端首屏 chunk 不含 EP，这里的下拉全部自己实现。</p>
 */
const session = useSessionStore()
const search = useSearchPanel()
const site = useSiteStore()
const route = useRoute()

/** 登录后回到当前这一页：门户顶栏的登录按钮不该把人甩到工作区 */
const loginHref = computed(() => appHref(`/login?redirect=${encodeURIComponent(route.fullPath)}`))
/** 注册页会接着自动登录，同样要接回原页，否则刚注册的人被丢进 /console 面对一个空工作区 */
const registerHref = computed(() => appHref(`/register?redirect=${encodeURIComponent(route.fullPath)}`))

const menuOpen = ref(false)
const userRef = ref<HTMLElement | null>(null)
const menuRef = ref<HTMLElement | null>(null)

onMounted(() => session.resolve())

function removeListeners() {
  document.removeEventListener('pointerdown', onDocPointerDown, true)
  document.removeEventListener('keydown', onKeydown)
}

onBeforeUnmount(removeListeners)

function onDocPointerDown(event: PointerEvent) {
  const target = event.target as Node | null
  if (userRef.value && target && userRef.value.contains(target)) {
    return
  }
  closeMenu()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') {
    return
  }
  closeMenu()
  userRef.value?.querySelector<HTMLElement>('.md-avatar-btn')?.focus()
}

/** 关闭只走这一条路径：改状态与摘监听绑在一起，否则再打开会挂上第二份监听 */
function closeMenu() {
  if (!menuOpen.value) return
  menuOpen.value = false
  removeListeners()
}

async function toggleMenu() {
  if (menuOpen.value) {
    closeMenu()
    return
  }
  menuOpen.value = true
  document.addEventListener('pointerdown', onDocPointerDown, true)
  document.addEventListener('keydown', onKeydown)
  await nextTick()
  placeMenu()
}

/**
 * 菜单用 fixed 定位并按触发点算位置：顶栏是 sticky 的，阅读页正文里还有自己的粘性工具条，
 * 绝对定位的菜单在窄屏上会被压在下面或溢出视口右沿。
 */
function placeMenu() {
  const menu = menuRef.value
  const trigger = userRef.value?.querySelector<HTMLElement>('.md-avatar-btn')
  if (!menu || !trigger) {
    return
  }
  const rect = trigger.getBoundingClientRect()
  menu.style.top = `${rect.bottom + 8}px`
  menu.style.left = `${Math.max(8, rect.right - menu.offsetWidth)}px`
}

async function logout() {
  await session.logout()
  window.location.href = appHref('/')
}
</script>

<template>
  <header class="md-topbar">
    <div class="md-topbar__inner">
      <router-link class="md-brand" to="/">
        <span class="md-brand__logo" :class="{ 'is-image': site.value.logoSrc }" aria-hidden="true">
          <img v-if="site.value.logoSrc" :src="site.value.logoSrc" alt="" />
          <svg v-else viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="#fff"
               stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H10a2 2 0 0 1 2 2v14a2 2 0 0 0-2-2H4z" />
            <path d="M20 5.5A1.5 1.5 0 0 0 18.5 4H14a2 2 0 0 0-2 2v14a2 2 0 0 1 2-2h6z" />
          </svg>
        </span>
        <span class="md-brand__text">
          <strong>{{ site.value.name }}</strong>
          <small v-if="site.value.subtitle">{{ site.value.subtitle }}</small>
        </span>
      </router-link>

      <nav class="md-topbar__actions">
        <button type="button" class="md-icon-btn" title="搜索（Ctrl K）" aria-label="搜索" @click="search.open">
          <Icon name="search" :size="17" />
        </button>

        <template v-if="!session.loggedIn">
          <!-- 注册开关的真值在后端，这里不预判：关着时后端那句 403 文案会直接显示出来 -->
          <a class="md-btn md-btn--ghost" :href="registerHref">
            <Icon name="plus" :size="13" />注册
          </a>
          <a class="md-btn md-btn--dark" :href="loginHref">
            <Icon name="login" :size="13" />登录
          </a>
        </template>

        <div v-else ref="userRef" class="md-user">
          <button
            type="button"
            class="md-avatar-btn"
            :aria-expanded="menuOpen"
            aria-haspopup="true"
            aria-label="账号菜单"
            @click="toggleMenu"
          >
            <span class="md-avatar">
              <img v-if="session.avatarSrc" :src="session.avatarSrc" :alt="session.displayName" />
              <template v-else>{{ session.initial }}</template>
            </span>
            <span class="md-avatar-btn__name">{{ session.displayName }}</span>
            <Icon name="chevronDown" :size="13" />
          </button>
          <div v-if="menuOpen" ref="menuRef" class="md-menu md-menu--fixed" role="menu">
            <div class="md-menu__head">
              <strong>{{ session.displayName }}</strong>
              <small>@{{ session.user?.username }}</small>
            </div>
            <a class="md-menu__item" role="menuitem" :href="appHref('/console')">
              <Icon name="dashboard" :size="14" />管理后台
            </a>
            <a class="md-menu__item" role="menuitem" :href="appHref('/account')">
              <Icon name="user" :size="14" />账号设置
            </a>
            <button type="button" class="md-menu__item md-menu__item--danger" role="menuitem" @click="logout">
              <Icon name="logout" :size="14" />退出登录
            </button>
          </div>
        </div>
      </nav>
    </div>
  </header>
</template>
