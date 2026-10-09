<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import MdIcon from '@/admin/components/MdIcon.vue'
import { tenantApi } from '@/admin/api'
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

/**
 * 当前地址是否带组织段。
 *
 * <p>整条侧边导航由它决定，而不是由「页面看起来像什么」决定：不带组织的管理页
 * （账号设置 / 发现组织 / 平台用户管理）如果渲染出「知识库管理」这类链接，点进去就是
 * {@code /console//kbs} 的空组织地址。{@code orgStore.org} 在没有组织段时保留上一次的值
 * （见 {@code enterAdmin}），所以判断只能用 route.params，不能用 store。</p>
 */
const currentOrg = computed(() => (route.params.org as string) || '')
const inOrg = computed(() => currentOrg.value !== '')
/**
 * 当前组织里我能做的动作：后端在 {@code /api/me} 的每个组织条目上带下来（规范 §4.2）。
 *
 * <p>导航项这一处与页面里的判定差一条：{@code org.current} 为空表示组织清单还没到，或这个
 * slug 根本不在我的清单里，那时宁可少给一个入口，也不要给一个点进去 403 的按钮 —— 页面内
 * 的判定读的是详情出参，那里空向量才是「后端没算」。</p>
 */
function orgCan(action: KbActionName): boolean {
  const granted = org.current?.myPermissions
  return !!granted && granted.includes(action)
}

/**
 * 侧边导航按「组织 / 个人」分组，两组在任何管理页都在。
 *
 * <p>之前是「进了组织只剩组织项，出了组织只剩个人项」，切到 /account 时整条导航换掉，
 * 人会觉得走错了地方。分组之后，位置变化只体现在高亮上。</p>
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
  if (!inOrg.value) {
    const groups = [
      { title: '个人', items: personal },
      { title: '工作区', items: [{ name: 'entry', label: '返回工作区', icon: 'home', path: '/console', tone: 'blue' }] }
    ]
    if (siteItems.length) groups.push({ title: '站点', items: siteItems })
    return groups
  }
  const at = (path: string) => `/console/${encodeURIComponent(currentOrg.value)}/${path}`
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
    {
      title: '站点',
      items: [{ name: 'portal', label: '打开门户', icon: 'external', path: '/', tone: 'green' }, ...siteItems]
    }
  ]
})

const activeName = computed(() => {
  if (route.name === 'kb-workspace') return 'kbs'
  return (route.name as string) || 'overview'
})

const orgLabel = computed(() => org.current?.name || currentOrg.value || '未选择组织')

const currentTitle = computed(() => (route.meta.title as string) || '管理后台')
const themeMode = computed(() => settings.value.themeMode)
const isDark = computed(() => document.documentElement.getAttribute('data-theme') === 'dark')

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

async function cycleTheme() {
  const order: Array<'light' | 'dark' | 'system'> = ['light', 'dark', 'system']
  const next = order[(order.indexOf(themeMode.value) + 1) % order.length]
  await settings.patch({ themeMode: next })
  ElMessage.success(next === 'system' ? '主题：跟随系统' : next === 'dark' ? '主题：深色' : '主题：浅色')
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

onMounted(async () => {
  settings.applyToDom()
  mobileMq.addEventListener('change', syncMobile)
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
})

onBeforeUnmount(() => {
  mobileMq.removeEventListener('change', syncMobile)
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
        <span class="md-sidebar__logo" :class="{ 'is-image': site.value.logoSrc }" aria-hidden="true">
          <img v-if="site.value.logoSrc" :src="site.value.logoSrc" alt="" />
          <svg v-else viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="#fff"
               stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H10a2 2 0 0 1 2 2v14a2 2 0 0 0-2-2H4z" />
            <path d="M20 5.5A1.5 1.5 0 0 0 18.5 4H14a2 2 0 0 0-2 2v14a2 2 0 0 1 2-2h6z" />
          </svg>
        </span>
        <span v-if="!navCollapsed" class="md-sidebar__title">
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
      <div v-if="!navCollapsed" class="md-sidebar__foot">v1.2.0 · {{ site.value.subtitle || site.value.name }}</div>
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
        <el-dropdown trigger="click" @command="onOrgCommand">
          <span class="md-header__org" :title="`当前组织：${orgLabel}`">
            <MdIcon name="building" :size="15" />
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
        <span class="md-header__title">{{ currentTitle }}</span>
        <div class="md-header__spacer" />
        <button type="button" class="md-header__icon-btn" :title="isDark ? '切到浅色' : '切到深色'" @click="cycleTheme">
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
  </div>
</template>
