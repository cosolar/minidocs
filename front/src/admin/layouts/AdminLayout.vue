<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import MdIcon from '@/admin/components/MdIcon.vue'
import { authApi, tenantApi } from '@/admin/api'
import { useAuthStore } from '@/admin/stores/auth'
import { useOrgStore } from '@/admin/stores/org'
import { useSettingsStore } from '@/shared/stores/settings'
import { useSiteStore } from '@/shared/stores/site'
import { appHref } from '@/shared/appBase'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const org = useOrgStore()
const settings = useSettingsStore()
const site = useSiteStore()

const collapsed = ref(false)
/** 组织菜单展开时翻转箭头，和菜单的开启动作对上 */
const orgMenuOpen = ref(false)

/**
 * 移动端（≤1024px，与 admin.css 的媒体查询同断点）把侧边导航改成抽屉。
 *
 * <p>固定宽度的侧栏在手机上会吃掉大半屏，正文只剩百来像素；桌面端那套「收起成 64px 图标栏」
 * 在这里也不成立——64px 的图标列依旧占宽且没有文字。所以移动端一律用浮层抽屉：
 * 默认收在屏外，点顶栏按钮滑出，配一层遮罩，选中导航或按 Esc 收回。</p>
 */
const mobileMq = window.matchMedia('(max-width: 1024px)')
const isMobile = ref(mobileMq.matches)
const mobileNavOpen = ref(false)

/** 抽屉里始终展示完整导航（图标 + 文字）；「收起态」只属于桌面端的窄栏形态 */
const navCollapsed = computed(() => collapsed.value && !isMobile.value)

function syncMobile(event: MediaQueryList | MediaQueryListEvent) {
  isMobile.value = event.matches
  // 从移动端切回桌面时把抽屉状态归零，否则再切回来会「自己开着」
  if (!event.matches) mobileNavOpen.value = false
}

function onGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') mobileNavOpen.value = false
}

/** 跟随系统档位下，系统明暗变化时按钮图标要跟着翻 */
function syncSystemDark(event: MediaQueryList | MediaQueryListEvent) {
  systemDark.value = event.matches
}

/**
 * 当前地址是否带组织段。
 *
 * <p>组织内的链接必须带组织段；不带组织的管理页（账号设置 / 发现组织 / 平台用户管理）
 * 不能拼出 {@code /console//kbs} 这样的空地址。{@code orgStore.org} 在没有组织段时会被
 * 守卫清空，所以判断只能用 route.params，不能用 store。</p>
 */
const currentOrg = computed(() => (route.params.org as string) || '')
const inOrg = computed(() => currentOrg.value !== '')

/**
 * 侧栏「组织」组指向哪个组织。
 *
 * <p>个人区（/account 等）没有组织段，但菜单不能跟着换一套 —— 否则从组织里点「账号设置」，
 * 左边整列入口突然消失，人会觉得走错了地方。这里回落到 {@code /api/me} 的
 * {@code lastTenantSlug}（仍在我名下时）或第一个组织，仅用于生成导航链接；API 层的组织
 * 上下文仍由守卫清空，个人页请求不会因此打到某个组织上。</p>
 */
const contextOrg = computed(() => currentOrg.value || org.entry)
const contextTenant = computed(
  () => org.tenants.find((tenant) => tenant.slug === contextOrg.value) ?? null
)

/**
 * 组织里我能做的动作：后端在 {@code /api/me} 的每个组织条目上带下来（规范 §4.2）。
 * 个人区读的是回落组织的动作集，菜单因此与组织内保持同一份。
 *
 * <p>导航项这一处与页面里的判定差一条：拿不到动作集时宁可少给一个入口，也不要给一个
 * 点进去 403 的按钮 —— 页面内的判定读的是详情出参，那里空向量才是「后端没算」。</p>
 */
function orgCan(action: KbActionName): boolean {
  const granted = contextTenant.value?.myPermissions
  return !!granted && granted.includes(action)
}

/**
 * 侧边导航固定为「组织 / 个人 / 站点」三组，切到账号设置这类个人页也不换。
 *
 * <p>个人区没有组织段，组织组按 {@link contextOrg} 回落到上次所在组织；没有可用组织时
 * 这一组不出现。顶栏则把组织切换器收掉 —— 那里显示「未选择组织」只会像出了错。</p>
 *
 * <p>「审计日志」这一项仍然按组织的动作向量决定给不给（规范 §4.2）：它不是「看起来像管理员」
 * 就该出现的东西，点进去 404 比少一个入口难看得多。</p>
 */
const navGroups = computed(() => {
  const personal = [
    { name: 'account', label: '账号设置', icon: 'user', path: '/account', tone: 'indigo' },
    { name: 'discover', label: '发现组织', icon: 'globe', path: '/discover', tone: 'sky' }
  ]
  if (auth.isPlatformAdmin) {
    personal.push({
      name: 'platform-users', label: '平台用户管理', icon: 'users', path: '/platform/users', tone: 'violet'
    })
  }
  /*
   * 站点设置改的是全部署的品牌，不属于任何组织，门槛是平台角色（后端 /api/platform/site 同一条）。
   * 非平台管理员连入口都不给 —— 点进去也只会被守卫弹回工作区。
   */
  const siteItems = auth.isPlatformAdmin
    ? [{
        name: 'platform-site', label: '站点设置', icon: 'settings', path: '/platform/site', tone: 'orange'
      }]
    : []
  const portalItem = { name: 'portal', label: '打开门户', icon: 'external', path: '/', tone: 'green' }
  const slug = contextOrg.value
  if (!slug) {
    // 一个组织都没有时才缺这一组；此时也没有可回到的工作区
    return [
      { title: '个人', items: personal },
      { title: '站点', items: [portalItem, ...siteItems] }
    ]
  }
  const at = (path: string) => `/console/${encodeURIComponent(slug)}/${path}`
  const orgItems = [
    { name: 'overview', label: '系统概览', icon: 'grid', path: at('overview'), tone: 'blue' },
    { name: 'kbs', label: '知识库管理', icon: 'books', path: at('kbs'), tone: 'teal' },
    { name: 'shares', label: '分享管理', icon: 'share', path: at('shares'), tone: 'violet' },
    { name: 'org-settings', label: '组织设置', icon: 'building', path: at('settings'), tone: 'amber' }
  ]
  if (orgCan('AUDIT_READ')) {
    orgItems.push({
      name: 'audit', label: '审计日志', icon: 'clock', path: at('audit'), tone: 'rose'
    })
  }
  return [
    { title: '组织', items: orgItems },
    { title: '个人', items: personal },
    { title: '站点', items: [portalItem, ...siteItems] }
  ]
})

const activeName = computed(() => {
  if (route.name === 'kb-workspace') return 'kbs'
  return (route.name as string) || 'overview'
})

/** 顶栏只在组织内渲染切换器，所以这里不需要「未选择组织」这种兜底文案 */
const orgLabel = computed(() => org.current?.name || currentOrg.value)

const currentTitle = computed(() => (route.meta.title as string) || '管理后台')
const themeMode = computed(() => settings.value.themeMode)
/**
 * 系统偏好只在 themeMode 为 system 时参与图标判定。
 *
 * <p>不能直接读 document.documentElement 的 data-theme：那个 computed 没有响应式依赖，
 * 只会求值一次，切换后图标不跟着变。</p>
 */
const systemPrefersDark = window.matchMedia('(prefers-color-scheme: dark)')
const systemDark = ref(systemPrefersDark.matches)
const isDark = computed(() =>
  themeMode.value === 'system' ? systemDark.value : themeMode.value === 'dark'
)

/**
 * 切换组织：整条地址换掉，落点由路由守卫统一记（{@code rememberLast}）。
 *
 * <p>这里不自己写 {@code lastTenantSlug}：守卫看得见所有进入方式（含直接输地址），两处写就会
 * 出现「地址栏是 A、偏好是 B」。该值只管下次登录先去哪，不参与任何权限判断。</p>
 */
async function switchTo(slug: string) {
  await router.push({ name: 'kbs', params: { org: slug } })
}

/** 组织下拉框里混了两个动作项，先分流再当 slug 用。 */
function onOrgCommand(command: string) {
  if (command === 'new-org') {
    createVisible.value = true
  } else if (command === '__discover') {
    router.push('/discover')
  } else {
    void switchTo(command)
  }
}

async function onSearch() {
  const value = keyword.value.trim()
  router.push({ name: 'kbs', params: { org: currentOrg.value }, query: value ? { keyword: value } : {} })
}

/** 顶栏只切明暗两档；「跟随系统」是账号设置里的偏好，不从这颗按钮进入 */
async function toggleTheme() {
  const next: 'light' | 'dark' = isDark.value ? 'light' : 'dark'
  await settings.patch({ themeMode: next })
  ElMessage.success(next === 'dark' ? '主题：深色' : '主题：浅色')
}

async function toggleSidebar() {
  collapsed.value = !collapsed.value
  await settings.patch({ showDocTree: !collapsed.value })
}

/** 顶栏那颗按钮两种语义：桌面端收/展窄栏，移动端开/合抽屉 */
function onToggleNav() {
  if (isMobile.value) {
    mobileNavOpen.value = !mobileNavOpen.value
    return
  }
  void toggleSidebar()
}

/* ------------------------------------------------------------- 新建组织 */
const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', slug: '', description: '', joinPolicy: 'request', discoverable: true })

async function submitCreate() {
  if (!createForm.name.trim()) {
    ElMessage.warning('请填写组织名称')
    return
  }
  creating.value = true
  try {
    const created = await tenantApi.create({
      name: createForm.name.trim(),
      slug: createForm.slug.trim() || undefined,
      description: createForm.description.trim() || undefined,
      joinPolicy: createForm.joinPolicy,
      discoverable: createForm.discoverable
    })
    createVisible.value = false
    await org.load(true)
    void settings.patch({ lastTenantSlug: created.slug })
    ElMessage.success('组织已创建')
    await router.push({ name: 'kbs', params: { org: created.slug } })
  } finally {
    creating.value = false
  }
}

async function onCommand(command: string) {
  if (command === 'logout') {
    await auth.logout()
    org.reset()
    router.push('/login')
  } else if (command === 'account') {
    router.push('/account')
  } else if (command === 'portal') {
    window.open(appHref('/'), '_blank')
  } else if (command === 'new-org') {
    createVisible.value = true
  }
}

/* ------------------------------------------------------------- 补验证邮箱 */
/*
 * 「没验证过却能进来」是真实状态，不是矛盾：平台管理员被登录闸门豁免，开关关掉时原本被卡住的人
 * 也会进来。这里提醒一次、可以关掉，不拦任何已有功能 —— 补验证是便利项，不是第二道门。
 *
 * <p>收件地址只由后端从登录态取，前端传不了别人的邮箱，所以这个按钮借不走一台发信机。</p>
 */
const verifyVisible = ref(false)
const verifySending = ref(false)

async function sendOwnVerification() {
  verifySending.value = true
  try {
    await authApi.resendOwnVerification()
    ElMessage.success('验证邮件已发送。没有收到请检查邮箱是否填写正确，以及邮件是否在垃圾邮件箱里')
    verifyVisible.value = false
  } finally {
    verifySending.value = false
  }
}

function goAccount() {
  verifyVisible.value = false
  router.push('/account')
}

onMounted(async () => {
  settings.applyToDom()
  mobileMq.addEventListener('change', syncMobile)
  systemPrefersDark.addEventListener('change', syncSystemDark)
  document.addEventListener('keydown', onGlobalKeydown)
  if (!auth.user) {
    try {
      await auth.loadProfile()
    } catch {
      /* 路由守卫会处理 */
    }
  }
  /* 组织清单只为渲染顶栏；失败不影响当前页，守卫已经放行说明成员门是过的 */
  void org.load().catch(() => undefined)
  if (!settings.value.showDocTree) collapsed.value = true
  if (auth.user?.emailVerified === false) {
    verifyVisible.value = true
  }
})

onBeforeUnmount(() => {
  mobileMq.removeEventListener('change', syncMobile)
  systemPrefersDark.removeEventListener('change', syncSystemDark)
  document.removeEventListener('keydown', onGlobalKeydown)
})

/* 移动端点完导航立刻收回抽屉；桌面端此值恒为 false，不产生副作用 */
watch(() => route.fullPath, () => {
  mobileNavOpen.value = false
})
</script>

<template>
  <div class="md-admin">
    <aside class="md-sidebar" :class="{ 'is-collapsed': navCollapsed, 'is-mobile-open': mobileNavOpen }">
      <router-link class="md-sidebar__brand" to="/console">
        <span class="md-sidebar__logo is-image" :style="site.logoStyle" aria-hidden="true">
          <img :src="site.brandSrc" alt="" />
        </span>
        <span v-if="!navCollapsed && !site.logoOnly" class="md-sidebar__title">
          {{ site.value.name }}<small>管理后台</small>
        </span>
      </router-link>
      <button
        v-if="isMobile"
        type="button"
        class="md-sidebar__close"
        title="收起导航"
        aria-label="收起导航"
        @click="mobileNavOpen = false"
      >
        <MdIcon name="close" :size="17" />
      </button>
      <nav class="md-sidebar__nav">
        <div v-for="group in navGroups" :key="group.title" class="md-navgroup">
          <p v-if="group.title && !navCollapsed" class="md-navgroup__title">{{ group.title }}</p>
          <!-- 用 router-link 而不是 href="javascript:void(0)"：中键 / 新标签页打开和键盘焦点都靠它 -->
          <router-link
            v-for="item in group.items"
            :key="item.name"
            class="md-navitem"
            :class="['md-navitem', `md-navitem--${item.tone || 'blue'}`, { 'is-active': activeName === item.name }]"
            :to="item.path"
            :title="navCollapsed ? item.label : undefined"
          >
            <MdIcon class="md-navitem__icon" :name="item.icon" :size="16" />
            <span v-if="!navCollapsed">{{ item.label }}</span>
          </router-link>
        </div>
      </nav>
      <!--
        侧栏底部：版本标识，同时是仓库入口。

        <p>做成链接而不是纯文字，因为「这是哪个版本、去哪看源码」是看到版本号时
        最自然的两个念头，纯文字会让其中一个白想。</p>

        <p>居中：这一行是页脚署名，不属于左侧导航的层级，居中后它不再与上方的
        入口列表争夺注意力。</p>
      -->
      <a
        v-if="!navCollapsed"
        class="md-sidebar__foot"
        href="https://github.com/cosolar/minidocs.git"
        target="_blank"
        rel="noopener noreferrer"
        title="在 GitHub 上查看 MiniDocs 仓库"
      >2026@MiniDocs</a>
    </aside>

    <div v-if="isMobile && mobileNavOpen" class="md-admin__mask" @click="mobileNavOpen = false" />

    <div class="md-main">
      <header class="md-header">
        <button
          type="button"
          class="md-header__icon-btn"
          :title="isMobile ? '打开导航' : (navCollapsed ? '展开侧边栏' : '收起侧边栏')"
          @click="onToggleNav"
        >
          <MdIcon :name="isMobile ? 'menu' : 'panel-left'" :size="17" />
        </button>
        <div class="md-header__context" :class="{ 'is-solo': !inOrg }">
          <!-- 个人区（账号设置等）没有组织上下文，整个切换器收掉，不留一个空的「未选择组织」 -->
          <template v-if="inOrg">
            <el-dropdown trigger="click" @command="onOrgCommand" @visible-change="orgMenuOpen = $event">
              <span
                class="md-header__org"
                :class="{ 'is-open': orgMenuOpen }"
                :title="`当前组织：${orgLabel}`"
              >
                <span class="md-header__org-mark"><MdIcon name="building" :size="14" /></span>
                <span class="md-header__org-name">{{ orgLabel }}</span>
                <MdIcon class="md-header__org-caret" name="chevron-down" :size="13" />
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="tenant in org.tenants"
                    :key="tenant.slug"
                    :command="tenant.slug"
                    :disabled="tenant.slug === currentOrg"
                  >
                    <span class="md-ad-switch__name">{{ tenant.name }}</span>
                    <span class="md-ad-chip" :class="`md-ad-chip--${tenant.role.toLowerCase()}`">{{ tenant.role }}</span>
                  </el-dropdown-item>
                  <el-dropdown-item command="new-org" divided>
                    <MdIcon name="plus" :size="14" />新建组织
                  </el-dropdown-item>
                  <el-dropdown-item command="__discover">
                    <MdIcon name="globe" :size="14" />发现其他组织…
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <span class="md-header__divider" aria-hidden="true" />
          </template>
          <span class="md-header__title">{{ currentTitle }}</span>
        </div>
        <div class="md-header__spacer" />
        <button type="button" class="md-header__icon-btn" :title="isDark ? '切到浅色' : '切到深色'" @click="toggleTheme">
          <MdIcon :name="isDark ? 'sun' : 'moon'" :size="17" />
        </button>
        <el-dropdown @command="onCommand">
          <div class="md-header__user">
            <span class="md-avatar-sm">
              <img v-if="auth.avatarSrc" :src="auth.avatarSrc" :alt="auth.nickname" />
              <template v-else>{{ (auth.nickname || 'U').slice(0, 1) }}</template>
            </span>
            <span class="md-header__user-name">{{ auth.nickname }}</span>
            <MdIcon name="chevron-down" :size="14" />
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="account">
                <MdIcon name="user" :size="14" />账号设置
              </el-dropdown-item>
              <el-dropdown-item command="new-org">
                <MdIcon name="plus" :size="14" />新建组织
              </el-dropdown-item>
              <el-dropdown-item command="portal" divided>
                <MdIcon name="external" :size="14" />打开门户
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>
                <MdIcon name="logout" :size="14" />退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>
      <section class="md-content">
        <router-view />
      </section>
    </div>

    <el-dialog v-model="createVisible" title="新建组织" width="460px">
      <el-form label-position="top">
        <el-form-item label="组织名称">
          <el-input v-model="createForm.name" maxlength="64" placeholder="研发部" />
        </el-form-item>
        <el-form-item label="标识 slug（可选，留空按名称生成；创建后不可修改）">
          <el-input v-model="createForm.slug" maxlength="48" placeholder="rd" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="createForm.description" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
        <el-form-item label="加入方式">
          <el-radio-group v-model="createForm.joinPolicy">
            <el-radio-button value="request">接受申请</el-radio-button>
            <el-radio-button value="invite_only">仅管理员邀请</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="出现在发现列表">
          <el-switch v-model="createForm.discoverable" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">
          <MdIcon name="close" :size="14" />取消
        </el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">
          <MdIcon name="check" :size="14" />创建
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="verifyVisible" title="补验证邮箱" width="440px">
      <p v-if="auth.user?.email" class="md-verify-tip">
        这个账号的邮箱 <strong>{{ auth.user.email }}</strong> 还没有验证过。
        站点开启邮箱验证后，未验证的账号无法登录（平台管理员除外），建议现在补一封验证邮件。
      </p>
      <p v-else class="md-verify-tip">
        这个账号还没有填写邮箱。站点开启邮箱验证后，没填邮箱的账号无法登录（平台管理员除外），
        建议先到账号设置补一个能收信的地址。
      </p>
      <template #footer>
        <el-button @click="verifyVisible = false">
          稍后再说
        </el-button>
        <el-button v-if="auth.user?.email" type="primary" :loading="verifySending" @click="sendOwnVerification">
          <MdIcon name="mail" :size="14" />发送验证邮件
        </el-button>
        <el-button v-else type="primary" @click="goAccount">
          <MdIcon name="user" :size="14" />去填邮箱
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
