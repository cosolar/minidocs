<script setup lang="ts">
/**
 * 分享面板：整库与单篇文档共用一份（规范 §2.4「分享权限等同写权限」）。
 *
 * <p>一个目标（库或文档路径）只有一条分享链接，所以「生成」与「更新」是同一个动作：后端按
 * 目标键 upsert，前端不去猜该发 POST 还是 PUT。撤销看的是分享创建者与组织管理员，
 * 这个结论由后端在 {@code canGovern} 上给，按钮才不会亮了却点不动。</p>
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { shareApi } from '@/admin/api'
import MdIcon from '@/admin/components/MdIcon.vue'
import ShareMenuPicker from '@/admin/components/ShareMenuPicker.vue'
import type { NavMenuItem, ShareVO } from '@/shared/api/types'

const props = defineProps<{
  modelValue: boolean
  /** 目标库标识：地址栏只带组织 + slug（规范 §4.2），所以这里不传 id */
  kbSlug: string
  /** 分享目标：省略即整库；给了文档路径就是单篇分享 */
  docPath?: string
  /** 面板标题里要显示的对象名 */
  title: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 链接状态变了：外层要刷新列表上的分享标记 */
  changed: []
}>()

const info = ref<ShareVO | null>(null)
const loading = ref(false)
const saving = ref(false)
const form = reactive({
  encrypted: false,
  portalScope: 'anonymous' as const,
  password: '',
  expiresIn: 'forever',
  /** 自定义短链：留空 = 自动生成。仅首次创建时生效，已有链接时这一项禁用 */
  token: '',
  /** 导航菜单：顺序即分享页顶栏顺序；空数组 = 不显示导航条 */
  menu: [] as NavMenuItem[]
})

/**
 * 自定义短链的可用性提示。
 *
 * <p>唯一的判据是「后端会不会收」：6-32 位、字母数字与 {@code -_}。唯一性由后端查（跨组织全局唯一），
 * 报错走全局拦截器弹提示，前端不自己猜 —— 猜出来的「可用」在并发下照样会被占。</p>
 */
const tokenHint = computed(() => {
  const value = form.token.trim()
  if (!value) return '留空自动生成，例如 interview-2026'
  if (!/^[A-Za-z0-9_-]{6,32}$/.test(value)) return '只能用字母、数字、下划线和连字符，长度 6-32 位'
  return info.value ? '改这里旧地址会立即失效' : '创建后仍可再改'
})

/** 格式不合规时直接禁用提交，免得发出去才被后端驳回 */
const tokenValid = computed(() => !form.token.trim() || /^[A-Za-z0-9_-]{6,32}$/.test(form.token.trim()))

/** 短链可以改，但「改了」这件事要先说清后果：旧链接会当场打不开 */
const tokenChanged = computed(
  () => !!info.value?.token && !!form.token.trim() && form.token.trim() !== info.value.token
)

/** 单篇分享没有「目录」可言，导航菜单只对整库分享开放 */
const kbScope = computed(() => !props.docPath)

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    info.value = null
    Object.assign(form, { encrypted: false, password: '', expiresIn: 'forever', token: '', menu: [], portalScope: 'anonymous' })
    loading.value = true
    try {
      // 读不到现有链接就当作没有：这条查询按目标键要 SHARE_CREATE，刚失去写权的人会 404
      const existing = await shareApi.info(props.kbSlug, props.docPath)
      info.value = existing || null
      if (existing) {
        form.encrypted = existing.encrypted
        form.portalScope = (existing.portalScope || 'anonymous') as PortalScope
        form.expiresIn = existing.expiresIn || 'forever'
        // 回填当前短链：改不改是用户的决定，看到自己填的是什么才谈得上改
        form.token = existing.token || ''
        // 后端下发的菜单项已带显示名，直接回填；重新保存时按路径再校验一次
        form.menu = (existing.menu || []).map((item) => ({ ...item }))
      }
    } catch {
      info.value = null
    } finally {
      loading.value = false
    }
  }
)

/** 状态码翻成中文：面板上直接读「有效 / 已过期」，而不是把 active 这类枚举值甩给用户 */
const stateText = computed(() => {
  const status = info.value?.status || ''
  return { active: '有效', expired: '已过期', revoked: '已撤销', invalid: '已失效' }[status] || status || '未知'
})

/** 三档色调：正常绿、过期琥珀、其余（撤销/失效）走灰，一眼能看出这条链接还能不能用 */
const stateTone = computed(() => {
  const status = info.value?.status
  return status === 'active' ? 'ok' : status === 'expired' ? 'warn' : 'muted'
})

/** 已有链接就报实际到期时间，没有就说明这条设置的含义 */
const expiresHint = computed(() => {
  const at = info.value?.expiresAt
  return at ? `当前到期时间：${at.replace('T', ' ').slice(0, 16)}` : '到期后链接自动失效，需重新生成'
})

/** 有链接时按钮做的是「更新」，没有时才是「生成」——同一个 upsert 接口，文案跟着目标变 */
const submitText = computed(() => (info.value ? '更新链接' : '生成链接'))

async function submit() {
  /*
   * 换短链的后果不可逆：链接可能已经发出去，对方书签、聊天记录、邮件里的旧地址会当场 404。
   * 所以这一步必须由用户点头，不能只靠 hint 文案提醒。
   */
  if (tokenChanged.value) {
    await ElMessageBox.confirm(
      `确定把短链从「${info.value?.token}」改成「${form.token.trim()}」？改完旧链接立即失效，已发出去的地址会打不开。`,
      '更换短链',
      { type: 'warning', confirmButtonText: '更换', cancelButtonText: '再想想' }
    )
  }
  saving.value = true
  try {
    info.value = await shareApi.create({
      kbSlug: props.kbSlug,
      scope: props.docPath ? 'doc' : 'kb',
      docPath: props.docPath || undefined,
      encrypted: form.encrypted,
      portalScope: form.portalScope,
      // 空串在这里一律不发：后端把 null 当「沿用原密码」，而清空密码是另一个动作
      password: form.password || undefined,
      expiresIn: form.expiresIn,
      // 自定义短链同理：留空就不发，走后端自动生成；已有链接时后端会拒绝改动
      token: form.token.trim() || undefined,
      // 整库分享才带菜单：空数组是有意义的「清掉菜单」，单篇分享干脆不发这个字段
      menu: kbScope.value ? form.menu : undefined
    })
    form.password = ''
    ElMessage.success('分享链接已就绪')
    emit('changed')
  } finally {
    saving.value = false
  }
}

async function revoke() {
  if (!info.value) return
  await ElMessageBox.confirm('撤销后该链接立即失效，且无法恢复。', '确认撤销分享？', {
    type: 'warning',
    confirmButtonText: '撤销',
    cancelButtonText: '取消'
  })
  await shareApi.revoke(info.value.token)
  info.value = null
  form.encrypted = false
  form.password = ''
  form.expiresIn = 'forever'
  form.menu = []
  ElMessage.success('已撤销')
  emit('changed')
}

async function copyUrl() {
  const url = info.value?.url
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('链接已复制')
  } catch {
    ElMessageBox.alert(url, '请手动复制链接', { confirmButtonText: '知道了' })
  }
}

function openUrl() {
  if (info.value?.url) {
    window.open(info.value.url, '_blank')
  }
}

/** 门户可见范围三档。label 说清「谁」，hint 补一句「不选会怎样」，避免作者凭字面猜。 */
type PortalScope = 'anonymous' | 'member' | 'maintainer'

const PORTAL_SCOPES = [
  { value: 'anonymous', label: '所有人（含未登录访客）' },
  { value: 'member', label: '仅登录用户（任何组织）' },
  { value: 'maintainer', label: '仅本库维护者' }
] as const

const portalScopeHint = computed(() => {
  switch (form.portalScope) {
    case 'member':
      return '门户列表里只有登录用户看得到；未登录的人即使拿到链接也会被引导去登录'
    case 'maintainer':
      return '只有本库的维护者能在门户里看到它 —— 相当于「发布给自己看」'
    default:
      return '任何人访问门户都能看到这个库'
  }
})
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="`分享「${title}」`"
    width="560px"
    align-center
    class="md-kb-dialog md-share-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="md-share">
      <!-- 已有链接：状态、访问数据、地址与动作收进同一块，读起来就是「一条链接」 -->
      <section v-if="info" class="md-share-link">
        <header class="md-share-link__head">
          <span class="md-share-state" :class="`is-${stateTone}`">
            <span class="md-share-state__dot" />{{ stateText }}
          </span>
          <span class="md-share-metric"><MdIcon name="eye" :size="13" />访问 {{ info.views }} 次</span>
          <span class="md-share-metric"><MdIcon name="users" :size="13" />独立访客 {{ info.uv }} 人</span>
        </header>
        <div class="md-share-link__row">
          <span class="md-share-link__url" :title="info.url">{{ info.url }}</span>
          <el-button size="small" @click="copyUrl">
            <MdIcon name="copy" :size="14" />复制
          </el-button>
          <el-button size="small" @click="openUrl">
            <MdIcon name="external" :size="14" />打开
          </el-button>
        </div>
      </section>

      <el-alert v-else type="info" :closable="false" show-icon class="md-share-empty">
        <template #title>尚未创建分享链接</template>
        <template #default>填好下面的设置后点「生成链接」，就会得到一条对外可读的地址。</template>
      </el-alert>

      <!-- 设置区：一行一项，说明与控件同排，比标签在上、控件在下的空档紧凑得多 -->
      <section class="md-share-panel">
        <h4 class="md-share-panel__title">分享设置</h4>

        <div class="md-share-field">
          <div class="md-share-field__text">
            <span class="md-share-field__label"><MdIcon name="link" :size="14" />自定义短链</span>
            <span class="md-share-field__hint">{{ tokenHint }}</span>
          </div>
          <!--
            宽度必须给死：这一行是「左文字 / 右控件」的 flex 布局，而 EP 的 .el-input 默认
            width:100%，不设宽度它会把左边 flex:1 的文字区挤成 0 宽 —— 标题就变成一字一行。
            同行的密码开关、有效期下拉都是固定宽度，控件宽度一致才像同一组设置。
          -->
          <el-input
            v-model="form.token"
            placeholder="留空自动生成，例如 interview-2026"
            maxlength="32"
            spellcheck="false"
            style="width: 240px"
          />
        </div>

        <div class="md-share-field">
          <div class="md-share-field__text">
            <span class="md-share-field__label"><MdIcon name="lock" :size="14" />访问密码</span>
            <span class="md-share-field__hint">开启后访客要输入密码才能阅读</span>
          </div>
          <el-switch v-model="form.encrypted" />
        </div>

        <div v-if="form.encrypted" class="md-share-field md-share-field--stack">
          <el-input v-model="form.password" show-password placeholder="留空表示沿用原密码" maxlength="64" />
        </div>

        <div class="md-share-field">
          <div class="md-share-field__text">
            <span class="md-share-field__label"><MdIcon name="clock" :size="14" />有效期</span>
            <span class="md-share-field__hint">{{ expiresHint }}</span>
          </div>
          <el-select v-model="form.expiresIn" style="width: 150px">
            <el-option label="永久有效" value="forever" />
            <el-option label="1 天" value="1d" />
            <el-option label="7 天" value="7d" />
            <el-option label="30 天" value="30d" />
          </el-select>
        </div>

        <!--
          门户可见范围：这一栏决定「谁能在门户里看到并点进这个库」。
          它与上面的访问密码是两层 —— 密码是「你知道的那串字符」，这里是「你有没有身份」，
          两者可以任意组合（登录用户 + 免密 = 任何注册过的人点链接即读）。
          只对整库分享有意义：单篇分享不进门户列表。
        -->
        <div v-if="kbScope" class="md-share-field md-share-field--stack">
          <div class="md-share-field__text">
            <span class="md-share-field__label"><MdIcon name="globe" :size="14" />门户可见范围</span>
            <span class="md-share-field__hint">{{ portalScopeHint }}</span>
          </div>
          <el-select v-model="form.portalScope" style="width: 100%">
            <el-option
              v-for="opt in PORTAL_SCOPES"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </div>
      </section>

      <!-- 导航菜单：只有整库分享才有「目录」可挑，单篇分享不显示这一块 -->
      <section v-if="kbScope" class="md-share-panel">
        <h4 class="md-share-panel__title">导航菜单</h4>
        <p class="md-share-panel__hint">
          勾选目录或文档作为分享页顶部的菜单项，再调整它们的先后顺序。点目录菜单会打开该目录下的第一篇文档。
          不选则分享页照旧展示完整目录树。
        </p>
        <p class="md-share-panel__hint">
          右边可以给每一项取一个<b>别名</b>：目录名是给你自己看的（{@code docs}、{@code notes}），
          读者看到的是导航条上的文字，两者没有必然关系。别名留空就用目录名，改目录名也不会冲掉它。
        </p>
        <ShareMenuPicker v-model="form.menu" :kb-slug="kbSlug" />
      </section>

      <p class="md-share-note">
        <MdIcon name="key" :size="13" />
        拿到链接的人只能读，改不了内容；对外可读等同于一次发布，所以能维护这个库的人才给这一项。
      </p>
    </div>

    <template #footer>
      <div class="md-share-foot">
        <el-button v-if="info?.canGovern" type="danger" plain @click="revoke">
          <MdIcon name="trash" :size="14" />撤销分享
        </el-button>
        <div class="md-share-foot__right">
          <el-button @click="emit('update:modelValue', false)">
            <MdIcon name="close" :size="14" />关闭
          </el-button>
          <el-button type="primary" :loading="saving" :disabled="!tokenValid" @click="submit">
            <MdIcon name="link" :size="14" />{{ submitText }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>
