<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '@/user/components/Icon.vue'
import ShareMenu from '@/user/components/ShareMenu.vue'
import ShareSettingsMenu from '@/user/components/ShareSettingsMenu.vue'
import KbGlyph from '@/shared/components/KbGlyph.vue'
import { useSiteStore } from '@/shared/stores/site'
import type { NavMenuItem } from '@/shared/api/types'

/**
 * 分享页顶栏。
 *
 * <p>门户顶栏（{@code TopBar.vue}）放的是「搜索 + 身份」，分享页两者都不该有：访客没有身份，
 * 站内搜索也不该把外部读者引到门户里去。所以这一条是独立的：品牌 + 导航菜单 + 分享 + 浏览量。</p>
 *
 * <p>顶栏这一行的顺序是「品牌 → 知识库 → 导航菜单 → 分享动作」：品牌回答「这是谁的站」，
 * 知识库回答「我在哪个库」，菜单回答「这个库从哪进」，动作是分享与浏览量。知识库名从左栏挪到
 * 品牌后面（{@code §2 知识库入口}），顺带做成左栏范围的开关。</p>
 *
 * <p>导航菜单紧挨知识库右侧、与顶栏同处一行 —— 分享者配的入口是「这一页去哪」，和品牌一样属于
 * 页面级导航，另起一行会白占一档竖向空间，还会把三栏的 sticky 偏移算复杂。</p>
 *
 * <p>不做收藏与点赞——它们要新增实体与接口，而分享页只读场景下「看到多少人读过」和
 * 「把链接转出去」已经覆盖了主要诉求。</p>
 */
const props = defineProps<{
  kbName: string
  views?: number
  expires?: string
  /** 分享者配置的导航菜单；为空时整块不渲染 */
  menu?: NavMenuItem[]
  /** 当前高亮的菜单项路径 */
  activePath?: string | null
  /** 知识库封面；缺键（没设封面）时用库名首字母兜底 */
  cover?: string
  docCount?: number
  updatedText?: string
  /**
   * 站点基址（后端下发）：顶栏「分享」复制绝对地址时用它，而不是 window.location.origin
   * （反代后面那可能正是内网地址）。缺省时回落到浏览器当前 origin。
   */
  siteBase?: string
  /** 左栏当前是不是全库目录：知识库按钮据此显示激活态 */
  allMode?: boolean
}>()

const emit = defineEmits<{ pick: [item: NavMenuItem]; all: [] }>()

const site = useSiteStore()
const route = useRoute()
const copied = ref(false)
let timer: number | undefined

onBeforeUnmount(() => window.clearTimeout(timer))

/** 没配导航菜单就没有「更窄的范围」可切回来，按钮只作身份标识，置灰 */
const canToggle = computed(() => (props.menu?.length || 0) > 0)

/** 副信息（篇数 / 更新时间）降级到 title，顶栏不堆第二行小字 */
const kbTitle = computed(() => {
  const bits = [props.kbName]
  if (props.docCount != null) bits.push(`共 ${props.docCount} 篇`)
  if (props.updatedText) bits.push(`更新 ${props.updatedText}`)
  bits.push(props.allMode ? '当前左栏是全库目录，点此回到栏目范围' : '点此让左栏加载全库所有文件')
  return bits.join(' · ')
})

/**
 * 分享地址带上当前文档：收到链接的人打开就是同一篇。
 *
 * <p>基址优先用后端下发的 {@code siteBase}（与管理台那条 URL 同一个来源）；拿不到才回落
 * {@code window.location.origin}。</p>
 */
const shareUrl = computed(() => `${props.siteBase || window.location.origin}${route.fullPath}`)

/**
 * 复制文本。
 *
 * <p>异步剪贴板 API 带 {@code [SecureContext]} 门：http 非 localhost 部署下
 * {@code navigator.clipboard} 压根不存在，权限被拒时也会抛错。少了兜底，
 * 这些环境里点「分享」毫无反应，读者只会以为按钮坏了。</p>
 */
async function copy(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 权限被拒，落到下面的兜底
    }
  }
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  area.remove()
  return ok
}

async function share() {
  // 系统分享优先（移动端能直接进微信/邮件），用户取消就安静收场
  if (typeof navigator.share === 'function') {
    try {
      await navigator.share({ title: props.kbName, url: shareUrl.value })
      return
    } catch {
      // 取消分享不是错误，但也不该接着弹「已复制」，直接返回
      return
    }
  }
  if (await copy(shareUrl.value)) {
    copied.value = true
    window.clearTimeout(timer)
    timer = window.setTimeout(() => (copied.value = false), 1600)
  }
}
</script>

<template>
  <header class="md-share__top">
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

    <!--
      知识库入口：顶栏里紧挨品牌，与导航菜单用一条竖线分隔。
      点它 = 让左栏加载全库所有文件（左栏默认是按当前菜单项收窄的，见 ShareView 的 asideTree），
      再点一次回到栏目范围。没配菜单时左栏本来就是全库，按钮退化成静态标识。
    -->
    <button
      type="button"
      class="md-share__kb"
      :class="{ 'is-active': allMode, 'is-static': !canToggle }"
      :aria-pressed="canToggle ? allMode : undefined"
      :aria-disabled="canToggle ? undefined : 'true'"
      :title="kbTitle"
      @click="emit('all')"
    >
      <span class="md-share__kb-cover" aria-hidden="true">
        <img v-if="cover" :src="cover" alt="" loading="lazy">
        <KbGlyph v-else :size="17" />
      </span>
      <span class="md-share__kb-text">
        <strong>{{ kbName }}</strong>
      </span>
      <!--
        ⌄ 箭头：说明这颗「知识库名」是个可点的范围开关（点它左栏在全库 / 栏目之间切），
        而不是一段静态文字。配了导航菜单才有得切，没配时按钮退化成标识，箭头也就不该出现。
      -->
      <Icon v-if="canToggle" name="chevronDown" :size="13" class="md-share__kb-caret" />
    </button>

    <ShareMenu
      v-if="menu?.length"
      :items="menu"
      :active-path="activePath"
      @pick="emit('pick', $event)"
    />

    <div class="md-share__actions">
      <!-- 分享有效期：原本压在正文标题上方，长文一滚就看不见；挪到顶栏右侧常驻 -->
      <span v-if="expires" class="md-share__expiry" :title="`分享有效期：${expires}`">
        <Icon name="clock" :size="14" />
        <span class="md-share__expiry-text">{{ expires }}</span>
      </span>

      <!-- 阅读设置：外观（明亮/暗黑）+ 正文字体，只存在访客这台浏览器上 -->
      <ShareSettingsMenu />

      <button
        type="button"
        class="md-share__action"
        :class="{ 'is-done': copied }"
        :title="copied ? '链接已复制' : '分享'"
        :aria-label="copied ? '链接已复制' : '分享'"
        @click="share"
      >
        <Icon :name="copied ? 'check' : 'share'" :size="17" />
      </button>

      <span v-if="views != null" class="md-share__views" title="浏览量">
        <Icon name="eye" :size="15" />
        <span>{{ views }}</span>
      </span>
    </div>
  </header>
</template>
