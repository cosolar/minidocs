<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { kbApi, orgApi, shareApi } from '@/admin/api'
import { portalKbUrl } from '@/shared/portal'
import { appHref } from '@/shared/appBase'
import { can } from '@/shared/api/caps'
import type { KbActionName, KbRosterVO, KbVO, OrgMemberVO } from '@/shared/api/types'
import ShareDialog from '@/admin/components/ShareDialog.vue'
import KbRowActions from '@/admin/components/KbRowActions.vue'
import MdIcon from '@/admin/components/MdIcon.vue'
import { useIsMobile } from '@/admin/composables/useIsMobile'
import {
  maintainScopeHint,
  maintainScopeLabel,
  publishStatusChipClass,
  publishStatusHint,
  publishStatusLabel,
  visibilityChipClass,
  visibilityHint,
  visibilityLabel
} from '@/shared/visibility'
import { useSettingsStore } from '@/shared/stores/settings'

const route = useRoute()
const router = useRouter()
const settings = useSettingsStore()

const org = computed(() => String(route.params.org || ''))

/**
 * 窄屏一律走网格（卡片）视图：列表视图那套表格把「操作」列固定在右侧，手机上会盖掉
 * 大半个屏幕。网格本来就存在，只是把它变成移动端的唯一读法，顺带藏掉视图切换。
 */
const isMobile = useIsMobile()

const loading = ref(false)
const list = ref<KbVO[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 12,
  keyword: (route.query.keyword as string) || '',
  sort: 'updated',
  visibility: '' as '' | KbVO['visibility'],
  favored: false
})

const formVisible = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const form = reactive({
  slug: '',
  name: '',
  description: '',
  visibility: 'org' as KbVO['visibility'],
  maintainScope: 'owner_only' as NonNullable<KbVO['maintainScope']>,
  tags: [] as string[],
  coverFile: null as File | null,
  coverPreview: '',
  /** 外链封面地址。与 coverFile 二选一：填了它就优先用链接，不再上传 */
  coverUrl: '',
  encrypted: false,
  password: '',
  expiresIn: 'forever',
  /** 内容来源：本地目录 / 云端 Git 仓库。只在新建时可选，建成后不再改（改来源等于换内容） */
  sourceType: 'local' as 'local' | 'git',
  gitUrl: '',
  gitBranch: 'main',
  gitUsername: '',
  gitToken: '',
  /** 编辑态回显用：库里是否已有令牌（后端只回布尔，不回明文） */
  gitTokenSet: false
})

/*
 * 标签编辑器的临时状态：草稿串、聚焦态、错误提示都不进 form ——
 * 它们不是要提交的数据，跟着弹窗开关走就行。
 */
const tagDraft = ref('')
const tagFocused = ref(false)
const tagError = ref('')
/** 中文输入法组字中：此时回车是「确认候选词」，不能当收标签用 */
const tagComposing = ref(false)
const tagInputRef = ref<HTMLInputElement | null>(null)
const TAG_MAX = 5
const TAG_LEN_MAX = 12

const shareVisible = ref(false)
const shareTarget = ref<KbVO | null>(null)

/* --------------------------------------------------------------- 权限（两条正交轴） */
const permVisible = ref(false)
const permTarget = ref<KbVO | null>(null)
const perms = reactive({ visibility: 'org' as KbVO['visibility'], maintainScope: 'owner_only' as NonNullable<KbVO['maintainScope']> })
const roster = ref<KbRosterVO[]>([])
const orgMembers = ref<OrgMemberVO[]>([])
const grant = reactive({ userId: 0, role: 'EDITOR' as KbRosterVO['role'] })
const permSaving = ref(false)
const rosterSaving = ref(false)

/** 名单只在 members 档下产生权利（规范 §2.3），org_all / owner_only 时要把这件事说明白。 */
const rosterEffective = computed(() => perms.maintainScope === 'members')
/**
 * 按钮显隐只看后端下发的动作向量（{@code shared/api/caps} 的 {@code can}），不在前端重抄判定
 * （规范 §2.6）。空向量在那里按「本次没算」放行：漏给值是后端的 bug，代价应当是多显示一个按钮
 * 并被后端 403 挡下，而不是把功能整片藏掉。
 */
const kbCan = (kb: KbVO | null | undefined, action: KbActionName) => can(kb?.myPermissions, action)
const canGovernKb = computed(() => kbCan(permTarget.value, 'KB_MEMBER_MANAGE'))
/** 可授权的人：组织成员里尚未在名单上的那些。 */
const grantCandidates = computed(() => {
  const granted = new Set(roster.value.map((row) => row.userId))
  return orgMembers.value.filter((member) => !granted.has(member.userId) && member.role !== 'OWNER')
})

const viewMode = computed(() => settings.value.kbView)

async function load() {
  loading.value = true
  try {
    const result = await kbApi.page({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      sort: query.sort,
      visibility: query.visibility || undefined,
      favored: query.favored || undefined
    })
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

watch(
  () => query.keyword,
  (() => {
    let timer: number | undefined
    return () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(() => {
        query.page = 1
        load()
      }, 320)
    }
  })(),
  { immediate: false }
)

watch(
  () => route.query.keyword,
  (value) => {
    query.keyword = (value as string) || ''
    query.page = 1
    load()
  }
)

function openCreate() {
  formMode.value = 'create'
  Object.assign(form, {
    slug: '', name: '', description: '', visibility: 'org', maintainScope: 'owner_only', tags: [],
    coverFile: null, coverPreview: '', coverUrl: '', coverBroken: false,
    encrypted: false, password: '', expiresIn: 'forever',
    sourceType: 'local', gitUrl: '', gitBranch: 'main', gitUsername: '', gitToken: '', gitTokenSet: false
  })
  resetTagEditor()
  formVisible.value = true
}

function openEdit(kb: KbVO) {
  formMode.value = 'edit'
  Object.assign(form, {
    slug: kb.slug,
    name: kb.name,
    description: kb.description || '',
    // 可见性在这里也照常显示与提交：后端只在值真的变了时才追加 KB_SET_VISIBILITY 判定，
    // 名单里的 EDITOR 保存改名不会撞墙，而试图放大可见范围会被拒 —— 这正是要的行为
    visibility: kb.visibility,
    maintainScope: kb.maintainScope || 'owner_only',
    tags: [...(kb.tags || [])],
    coverFile: null,
    coverPreview: kb.coverSrc || '',
    // 已存的外链回填进输入框：只回填预览图的话，作者看不到那条链接、也没法改
    coverUrl: isExternalCover(kb.coverSrc) ? kb.coverSrc : '',
    coverBroken: false,
    encrypted: false,
    password: '',
    expiresIn: 'forever',
    sourceType: kb.sourceType === 'git' ? 'git' : 'local',
    gitUrl: kb.git?.url || '',
    gitBranch: kb.git?.branch || 'main',
    gitUsername: kb.git?.username || '',
    gitToken: '',
    gitTokenSet: Boolean(kb.git?.tokenSet)
  })
  resetTagEditor()
  formVisible.value = true
}

function onCoverChange(file: { raw: File }) {
  form.coverFile = file.raw
  form.coverPreview = URL.createObjectURL(file.raw)
  // 上传与外链互斥：选了文件就把链接清掉，否则保存时两套都发、后写的赢，
  // 作者会以为上传没生效
  form.coverUrl = ''
}

/**
 * 预览图加载失败。
 *
 * <p>只在预览阶段提示、不阻止提交：外链图在作者这边加载不出来，常见原因是对方的
 * 防盗链按Referer 拦——而访客那边可能又能正常显示。此时拦住提交等于让作者为一个
 * 别人看不到的问题反复折腾。</p>
 */
function onCoverPreviewError() {
  coverBroken.value = true
}

function clearCover() {
  form.coverFile = null
  form.coverUrl = ''
  form.coverPreview = ''
  coverBroken.value = false
}

/** 已存的封面是不是外链（上传的存的是库内相对路径，形如 assets/cover-xxx.png） */
function isExternalCover(src?: string): boolean {
  return Boolean(src && /^https?:\/\//i.test(src))
}

/**
 * 外链地址变化时同步预览。
 *
 * <p>不校验图片能不能真的加载出来：跨域防盗链会让外链图在本地能显示、在访客那边
 * 404，提交前预检反而会给出假的失败结论。这里只挡明显的非 http/https。</p>
 */
function onCoverUrlInput() {
  const v = form.coverUrl.trim()
  if (!v) {
    form.coverPreview = ''
  } else if (/^https?:\/\/\S+$/i.test(v)) {
    form.coverPreview = v
    form.coverFile = null
  }
}

async function submitForm() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入知识库名称')
    return
  }
  const isCreate = formMode.value === 'create'
  const cloud = isCreate && form.sourceType === 'git'
  if (cloud && !form.gitUrl.trim()) {
    ElMessage.warning('请填写 Git 仓库地址')
    return
  }
  const payload: Record<string, unknown> = {
    name: form.name.trim(),
    description: form.description.trim() || undefined,
    visibility: form.visibility,
    tags: form.tags.filter((tag) => tag && tag.trim())
  }
  // 档位是建库时一次给定的（否则要两步写，第二步失败会留下一个档位与意图相反的库）；
  // 已存在的库改档位走「权限」面板的专用接口，那里才有 KB_MEMBER_MANAGE 的判定与留痕
  if (isCreate) {
    payload.maintainScope = form.maintainScope
    payload.sourceType = form.sourceType
    if (cloud) {
      payload.gitUrl = form.gitUrl.trim()
      payload.gitBranch = form.gitBranch.trim() || undefined
      payload.gitUsername = form.gitUsername.trim() || undefined
      // 空令牌不发：后端把「没填」与「填了空串」都当没配，多发一个空字段只会让请求看起来像要清空凭证
      payload.gitToken = form.gitToken.trim() || undefined
    }
  }
  try {
    const kb = isCreate ? await kbApi.create(payload) : await kbApi.update(form.slug, payload)
    if (form.coverFile) {
      await kbApi.uploadCover(kb.slug, form.coverFile)
    } else if (form.coverUrl.trim()) {
      // 外链封面单独一次调用：它落在 knowledge_base.cover_url，与库元信息的 payload 是两条路
      await kbApi.setCoverUrl(kb.slug, form.coverUrl.trim())
    } else if (!isCreate) {
      // 编辑态两个都空 = 清掉封面。不加这一条的话，粘错了地址就只能重新上传一张盖掉它
      await kbApi.clearCover(kb.slug)
    }
    if (form.encrypted || form.password) {
      await shareApi.create({
        kbSlug: kb.slug,
        scope: 'kb',
        encrypted: form.encrypted,
        password: form.password || undefined,
        expiresIn: form.expiresIn
      })
    }
    ElMessage.success(
      isCreate && form.sourceType === 'git'
        ? '云端知识库已创建，正在后台拉取仓库，可在工作区查看进度'
        : isCreate && form.maintainScope === 'members'
          ? '知识库已创建。维护名单还是空的，记得在「权限」里添加可写的人。'
          : isCreate
            ? '知识库已创建，尚未发布到门户 —— 需要对外分享请点这一行的「分享」'
            : '已保存'
    )
    formVisible.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

async function remove(kb: KbVO) {
  await ElMessageBox.confirm(
    `删除后磁盘目录 ${kb.directoryPath || `vaults/${kb.slug}`} 与全部分享链接将一并清除，且不可恢复。`,
    `确认删除「${kb.name}」？`,
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
  )
  await kbApi.remove(kb.slug)
  ElMessage.success('已删除')
  await load()
}

async function toggleFavorite(kb: KbVO) {
  const result = await kbApi.favorite(kb.slug, kb.favored)
  kb.favored = result.favored
  ElMessage.success(kb.favored ? '已收藏' : '已取消收藏')
}

/** 打开权限面板：档位与名单一起看，因为名单只在 members 档下才有意义。 */
async function openPerms(kb: KbVO) {
  permTarget.value = { ...kb }
  perms.visibility = kb.visibility
  perms.maintainScope = kb.maintainScope || 'owner_only'
  roster.value = []
  orgMembers.value = []
  grant.userId = 0
  grant.role = 'EDITOR'
  permVisible.value = true
  try {
    roster.value = await kbApi.roster(kb.slug)
    if (canGovernKb.value) {
      orgMembers.value = await orgApi.members()
    }
  } catch {
    /* 拿不到名单说明这一行没有治理权，面板上的档位部分仍然可用 */
  }
}

async function savePerms() {
  const kb = permTarget.value
  if (!kb) return
  permSaving.value = true
  try {
    if (perms.maintainScope !== kb.maintainScope) {
      await kbApi.setMaintainScope(kb.slug, perms.maintainScope)
    }
    if (perms.visibility !== kb.visibility) {
      await kbApi.update(kb.slug, { visibility: perms.visibility })
    }
    ElMessage.success('权限已更新')
    permVisible.value = false
    await load()
  } finally {
    permSaving.value = false
  }
}

async function grantRoster(userId: number, role: KbRosterVO['role'] | null) {
  const kb = permTarget.value
  if (!kb) return
  if (!userId) {
    ElMessage.warning('请选择要授权的人')
    return
  }
  rosterSaving.value = true
  try {
    const row = await kbApi.grantRoster(kb.slug, userId, role)
    if (role === null) {
      roster.value = roster.value.filter((item) => item.userId !== userId)
      grant.userId = 0
      ElMessage.success('已撤销该人的名单授权')
    } else {
      const index = roster.value.findIndex((item) => item.userId === row.userId)
      if (index === -1) roster.value.push(row)
      else roster.value.splice(index, 1, row)
      grant.userId = 0
      ElMessage.success(role === 'EDITOR' ? '已授予编辑权' : '已改为只读')
    }
    /* 档位与名单共同决定这一行的权限向量，列表上的按钮显隐要跟着重取 */
    await load()
  } finally {
    rosterSaving.value = false
  }
}

async function revokeRoster(row: KbRosterVO) {
  await ElMessageBox.confirm(
    `撤销 ${row.displayName || row.username} 在这个库上的维护授权？他仍是组织成员，其它库不受影响。`,
    '撤销授权',
    { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' }
  )
  await grantRoster(row.userId, null)
}

/** 打开面板就完了：现有链接与设置由 ShareDialog 自己按目标键读，两个入口共用一份逻辑 */
function openShare(kb: KbVO) {
  shareTarget.value = kb
  shareVisible.value = true
}

async function copyUrl(url?: string) {
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('链接已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择链接')
  }
}

async function setView(mode: 'grid' | 'list') {
  await settings.patch({ kbView: mode })
}

function portalUrl(kb: KbVO) {
  // 门户阅读页按 /kb/{org}/{slug} 定位：slug 只在组织内唯一，少一段就可能撞到别人的库
  return `${window.location.origin}${appHref(portalKbUrl(kb.tenantSlug, kb.slug))}`
}

function openWorkspace(kb: KbVO) {
  router.push({ name: 'kb-workspace', params: { org: org.value, slug: kb.slug } })
}

function openUrl(url?: string) {
  if (url) window.open(url, '_blank')
}

/* ------------------------------------------------------------------ 标签编辑器 */
function resetTagEditor() {
  tagDraft.value = ''
  tagError.value = ''
  tagFocused.value = false
  tagComposing.value = false
}

/** 点容器任意处都落到输入框上，省得用户非得戳中那根细线 */
function focusTagInput() {
  tagInputRef.value?.focus()
}

function removeTag(index: number) {
  form.tags.splice(index, 1)
  tagError.value = ''
}

/**
 * 把草稿串收成一个标签。
 *
 * <p>长度与数量都由控件本身挡掉了（输入框 maxlength、满 5 个就不渲染输入框），
 * 这里只剩「重复」一种需要出言提醒 —— 不然用户会以为回车坏了。</p>
 */
function commitTag() {
  const value = tagDraft.value.trim()
  if (!value) return
  if (form.tags.includes(value)) {
    tagError.value = `「${value}」已经在列表里了`
    tagDraft.value = ''
    return
  }
  form.tags.push(value)
  tagDraft.value = ''
  tagError.value = ''
}

/**
 * 中文输入法组字中敲回车是在选候选词，只有组字结束后才该收标签 ——
 * 否则「并发」还没上屏就先被收成拼音串了。
 */
function onTagEnter(event: KeyboardEvent) {
  if (event.isComposing || tagComposing.value) return
  event.preventDefault()
  commitTag()
}

/** 粘「a, b, c」这类串时按分隔符拆开一次收完，省得一个个敲 */
function onTagPaste(event: ClipboardEvent) {
  const text = event.clipboardData?.getData('text') ?? ''
  if (!/[,，;；\s]/.test(text)) return
  event.preventDefault()
  for (const piece of text.split(/[,，;；\s]+/)) {
    if (form.tags.length >= TAG_MAX) break
    const value = piece.trim().slice(0, TAG_LEN_MAX)
    if (!value || form.tags.includes(value)) continue
    form.tags.push(value)
  }
  tagDraft.value = ''
  tagError.value = ''
}

/** 输入框空着时按退格删掉最后一个标签：标签编辑器的通用手感 */
function onTagBackspace() {
  if (tagComposing.value) return
  if (tagDraft.value) return
  if (tagError.value) {
    tagError.value = ''
    return
  }
  if (form.tags.length) form.tags.pop()
}

onMounted(async () => {
  await load()
  /*
   * 门户顶栏的「新建知识库」以 ?new=1 直达（组织只能从地址栏来，见 EntryView 的改写）。
   * 弹窗开完就把标记擦掉，否则刷新一次就再弹一次。
   */
  if (route.query.new) {
    openCreate()
    void router.replace({ query: {} })
  }
})

/** 顶栏切换组织时组件被复用，参数变了必须重查：否则会看到上一个组织的库。 */
watch(org, () => {
  query.page = 1
  void load()
})
</script>

<template>
  <div class="md-card-panel">
    <div class="md-panel-head">
      <el-input v-model="query.keyword" placeholder="搜索名称 / 描述 / 标签" clearable style="width: 240px" />
      <el-select v-model="query.visibility" placeholder="全部可见范围" clearable style="width: 160px" @change="load">
        <el-option label="所有登录用户" value="public" />
        <el-option label="本组织成员" value="org" />
        <el-option label="仅维护名单" value="private" />
      </el-select>
      <el-select v-model="query.sort" style="width: 140px" @change="load">
        <el-option label="最近更新" value="updated" />
        <el-option label="创建时间" value="created" />
        <el-option label="名称" value="name" />
      </el-select>
      <el-checkbox v-model="query.favored" @change="load">仅看收藏</el-checkbox>
      <span class="md-spacer" />
      <el-radio-group
        v-if="!isMobile"
        :model-value="viewMode"
        size="small"
        @change="(value) => setView(value as 'grid' | 'list')"
      >
        <el-radio-button value="grid">网格</el-radio-button>
        <el-radio-button value="list">列表</el-radio-button>
      </el-radio-group>
      <el-button type="primary" @click="openCreate">
        <MdIcon name="plus" :size="14" />新建知识库
      </el-button>
    </div>

    <div class="md-panel-body" v-loading="loading">
      <el-empty v-if="!loading && list.length === 0" description="暂无知识库" />

      <!--
        列表：一行一个库，把「谁能读 / 谁能改 / 多少篇」并排成列，扫一眼就能比对。
        这是网格的另一种读法，不是另一套功能，所以动作列直接复用 KbRowActions。
      -->
      <!--
        列宽是按「固定列之和 + 弹性列下限 ≤ 容器」配的，别随手加宽：
        el-table 用的是 table-layout: fixed，一旦列之和超过容器宽度就变成内部横向滚动，
        而「操作」列是 fixed="right" 的浮层——它会正好盖住「更新」那一格，
        日期看着像被截成了「2026-」。

        「操作」列要放下「进入/分享/编辑/权限/更多」五个动作且不折行：实测每个链接式按钮
        50px、间距 8px，一行需 5×50 + 4×8 = 282px，加上单元格左右各 12px 的内边距，
        列宽下限是 306px（原来给 236px，只有 212px 可用，必然折成两行）。
        这 76px 只能从「操作」自己身上出——「可见性/可写/篇数/更新」都已贴着文案宽度
        （「全组织可写」五个字、日期串 16 个字符），再收就会折行或截断。
      -->
      <el-table v-else-if="viewMode === 'list' && !isMobile" :data="list" style="width: 100%">
        <el-table-column label="名称" min-width="190">
          <template #default="{ row }">
            <div class="md-kb-cell__name">
              <span class="md-kb-cell__thumb" :class="{ 'has-cover': row.coverSrc }">
                <img v-if="row.coverSrc" :src="row.coverSrc" alt="" loading="lazy">
              </span>
              <span class="md-kb-cell__text">
                <strong>
                  {{ row.name }}
                  <MdIcon v-if="row.favored" name="star" :size="13" class="md-kb-cell__star" title="已收藏" />
                  <span v-if="row.sourceType === 'git'" class="md-ad-chip md-ad-chip--cloud" title="绑定线上 Git 仓库">云端</span>
                </strong>
                <small v-if="row.directoryPath">{{ row.directoryPath }}</small>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="可见范围" width="104">
          <template #default="{ row }">
            <span
              class="md-ad-chip"
              :class="visibilityChipClass(row.visibility)"
              :title="visibilityHint(row.visibility)"
            >
              {{ visibilityLabel(row.visibility) }}
            </span>
          </template>
        </el-table-column>
        <!--
          发布状态独立成列，不与「可见范围」合并成一列「公开/私有」：
          前者答的是「平台内谁能读」，后者答的是「门户上出去了没有、要不要口令」，
          两者正交（私有库可以已发布，公开库也可以没发布）。合成一列就回到老问题上去了。
        -->
        <el-table-column label="发布状态" width="116">
          <template #default="{ row }">
            <span
              class="md-ad-chip"
              :class="publishStatusChipClass(row.shareStatus)"
              :title="publishStatusHint(row.shareStatus)"
            >
              {{ publishStatusLabel(row.shareStatus) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="可写" width="92">
          <template #default="{ row }">
            <span :title="maintainScopeHint(row.maintainScope)">{{ maintainScopeLabel(row.maintainScope) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="篇数" width="56" align="center">
          <template #default="{ row }">{{ row.docCount }}</template>
        </el-table-column>
        <el-table-column label="更新" width="140">
          <template #default="{ row }">{{ row.updatedText }}</template>
        </el-table-column>
        <el-table-column label="操作" width="312" fixed="right">
          <template #default="{ row }">
            <KbRowActions
              :kb="row"
              dense
              @workspace="openWorkspace(row)"
              @share="openShare(row)"
              @edit="openEdit(row)"
              @perms="openPerms(row)"
              @favorite="toggleFavorite(row)"
              @copy="copyUrl(portalUrl(row))"
              @remove="remove(row)"
            />
          </template>
        </el-table-column>
      </el-table>

      <div v-else class="md-kb-grid">
        <div v-for="kb in list" :key="kb.id" class="md-kb-card">
          <div class="md-kb-card__cover">
            <img v-if="kb.coverSrc" :src="kb.coverSrc" :alt="kb.name" loading="lazy" />
          </div>
          <div class="md-kb-card__body">
            <div class="md-kb-card__title">
              <span class="md-kb-card__name" :title="kb.name">{{ kb.name }}</span>
              <MdIcon v-if="kb.favored" name="star" :size="13" class="md-kb-card__fav" title="已收藏" />
            </div>
            <!-- 来源、可见范围、发布状态、标签同属「这库是什么」的元信息，合成一行小章；标题行只留名字 -->
            <div class="md-kb-card__chips">
              <span
                v-if="kb.sourceType === 'git'"
                class="md-ad-chip md-ad-chip--cloud"
                title="绑定线上 Git 仓库"
              >云端</span>
              <span class="md-ad-chip" :class="visibilityChipClass(kb.visibility)" :title="visibilityHint(kb.visibility)">
                {{ visibilityLabel(kb.visibility) }}
              </span>
              <span
                class="md-ad-chip"
                :class="publishStatusChipClass(kb.shareStatus)"
                :title="publishStatusHint(kb.shareStatus)"
              >{{ publishStatusLabel(kb.shareStatus) }}</span>
              <span v-for="tag in kb.tags" :key="tag" class="md-ad-chip md-ad-chip--tag">{{ tag }}</span>
            </div>
            <p class="md-kb-card__desc">{{ kb.description || '暂无描述' }}</p>
            <div class="md-kb-card__meta">
              <span>{{ kb.docCount }} 篇</span>
              <span :title="maintainScopeHint(kb.maintainScope)">可写：{{ maintainScopeLabel(kb.maintainScope) }}</span>
              <span>{{ kb.updatedText }}</span>
            </div>
          </div>
          <div class="md-kb-card__actions">
            <KbRowActions
              :kb="kb"
              @workspace="openWorkspace(kb)"
              @share="openShare(kb)"
              @edit="openEdit(kb)"
              @perms="openPerms(kb)"
              @favorite="toggleFavorite(kb)"
              @copy="copyUrl(portalUrl(kb))"
              @remove="remove(kb)"
            />
          </div>
        </div>
      </div>

      <div style="display: flex; justify-content: flex-end; margin-top: 16px">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[12, 24, 48]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load"
        />
      </div>
    </div>

    <!-- 新建 / 编辑 -->
    <!--
      弹窗本身定高（max-height 见 admin.css 的 .md-kb-dialog），正文区内部滚动：
      这样长表单永远不会把整页顶出一条滚动条。align-center 会让遮罩层变成 flex 容器，
      弹窗因此垂直居中，且内容超出时由正文区自己消化。
    -->
    <el-dialog
      v-model="formVisible"
      :title="formMode === 'create' ? '新建知识库' : '编辑知识库'"
      width="720px"
      class="md-kb-dialog"
      align-center
      :close-on-click-modal="false"
    >
      <el-form label-position="top" class="md-kb-form">
        <section class="md-kb-group">
          <h4 class="md-kb-group__title">基本信息</h4>
          <el-form-item label="名称" required>
            <el-input v-model="form.name" maxlength="64" show-word-limit placeholder="例如：产品手册" />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" show-word-limit />
          </el-form-item>
        </section>

        <section v-if="formMode === 'create'" class="md-kb-group">
          <h4 class="md-kb-group__title">内容来源</h4>
          <el-form-item>
            <el-radio-group v-model="form.sourceType">
              <el-radio-button value="local">本地目录</el-radio-button>
              <el-radio-button value="git">云端 Git 仓库</el-radio-button>
            </el-radio-group>
            <div class="md-form-hint">
              {{ form.sourceType === 'git'
                ? '把线上仓库克隆到服务器作为工作副本（后台进行，创建后立刻返回）；在工作区里可以「拉取」远程更新、「提交并推送」本地改动。'
                : '只在服务器上建一个空目录，内容全部由本平台管理。' }}
            </div>
          </el-form-item>
          <template v-if="form.sourceType === 'git'">
            <el-form-item label="仓库地址（HTTPS）" required>
              <el-input v-model="form.gitUrl" maxlength="512" placeholder="https://github.com/your-org/your-repo.git" />
            </el-form-item>
            <div class="md-kb-cols">
              <el-form-item label="分支">
                <el-input v-model="form.gitBranch" maxlength="128" placeholder="main" />
              </el-form-item>
              <el-form-item label="用户名">
                <el-input v-model="form.gitUsername" maxlength="128" placeholder="公开仓库可留空" />
              </el-form-item>
            </div>
            <el-form-item label="访问令牌">
              <el-input v-model="form.gitToken" type="password" show-password maxlength="512" placeholder="私有仓库必填" />
              <div class="md-form-hint">
                令牌加密后落库，保存后不再回显，只用于服务端访问仓库。地址里若带了 <code>user:token@</code>，入库时会自动剥掉。
              </div>
            </el-form-item>
            <el-alert
              type="info"
              :closable="false"
              show-icon
              style="margin-bottom: 4px"
              title="创建后立即返回，代码在后台拉取；地址或分支填错时库仍会建好，失败原因显示在工作区顶部，点「拉取」可重试。"
            />
          </template>
        </section>

        <section v-else-if="formMode === 'edit' && form.sourceType === 'git'" class="md-kb-group">
          <h4 class="md-kb-group__title">云端仓库</h4>
          <el-form-item label="仓库地址">
            <el-input :model-value="form.gitUrl" disabled />
            <div class="md-form-hint">
              分支 {{ form.gitBranch }} · 访问令牌{{ form.gitTokenSet ? '已配置' : '未配置' }} · 来源在建库时确定，之后不可更改
            </div>
          </el-form-item>
        </section>

        <section class="md-kb-group">
          <h4 class="md-kb-group__title">可见范围与维护权限</h4>
          <div class="md-kb-cols">
            <el-form-item label="可见范围（平台内谁能读）">
              <el-radio-group v-model="form.visibility">
                <el-radio-button value="org">本组织成员</el-radio-button>
                <el-radio-button value="private">仅维护名单</el-radio-button>
                <el-radio-button value="public">所有登录用户</el-radio-button>
              </el-radio-group>
              <div class="md-form-hint">{{ visibilityHint(form.visibility) }}</div>
            </el-form-item>
            <el-form-item :label="formMode === 'create' ? '维护档位（谁能改）' : '维护档位'">
              <el-radio-group v-if="formMode === 'create'" v-model="form.maintainScope">
                <el-radio-button value="owner_only">仅我自己</el-radio-button>
                <el-radio-button value="members">维护名单</el-radio-button>
                <el-radio-button value="org_all">全组织可写</el-radio-button>
              </el-radio-group>
              <el-input v-else :model-value="maintainScopeLabel(form.maintainScope)" disabled />
              <div class="md-form-hint">
                {{ formMode === 'create'
                  ? maintainScopeHint(form.maintainScope)
                  : '改档位与名单在卡片的「权限」里，那是另一项治理动作，需要单独留痕。' }}
              </div>
            </el-form-item>
          </div>
          <!--
            这一段是整组里最要紧的一句话：上面两个下拉都只在「平台内」生效，
            而用户建完库最想要的「让外面的人看到」属于发布那一层。
            少了它，「可见范围选了所有登录用户但门户上搜不到」就会被当成 bug。
          -->
          <p class="md-form-hint md-kb-group__note">
            以上两项都只作用于<b>平台内</b>。要让这个库出现在门户上、让不登录的人也能访问，
            还需要另外创建一份<b>整库分享链接</b>（卡片右侧「分享」）；链接是否设访问密码，
            决定它在门户上显示为「共享」还是「加密」。这两层互相独立。
          </p>
        </section>

        <section class="md-kb-group">
          <h4 class="md-kb-group__title">展示</h4>
          <div class="md-kb-cols">
            <el-form-item label="标签（最多 5 个，单个 ≤ 12 字）">
              <!--
                标签与输入框同处一个边框内：视觉上是一根「标签输入框」，而不是
                「一堆标签 + 旁边一根孤立的小输入框」。点容器任意处都能落焦。
              -->
              <div
                class="md-tag-field"
                :class="{ 'is-focus': tagFocused, 'is-error': !!tagError }"
                @click="focusTagInput"
              >
                <span v-for="(tag, index) in form.tags" :key="`${tag}-${index}`" class="md-tag-chip">
                  <span class="md-tag-chip__text">{{ tag }}</span>
                  <button
                    type="button"
                    class="md-tag-chip__del"
                    :title="`移除「${tag}」`"
                    @mousedown.prevent
                    @click.stop="removeTag(index)"
                  >
                    <MdIcon name="close" :size="10" />
                  </button>
                </span>
                <input
                  v-if="form.tags.length < TAG_MAX"
                  ref="tagInputRef"
                  v-model="tagDraft"
                  class="md-tag-field__input"
                  :placeholder="form.tags.length ? '继续添加…' : '输入后回车添加'"
                  :maxlength="TAG_LEN_MAX"
                  @focus="tagFocused = true"
                  @blur="tagFocused = false"
                  @compositionstart="tagComposing = true"
                  @compositionend="tagComposing = false"
                  @keydown.enter="onTagEnter"
                  @keydown.backspace="onTagBackspace"
                  @paste="onTagPaste"
                />
                <span v-else class="md-tag-field__full">已满，删一个再加</span>
                <span class="md-tag-field__count">{{ form.tags.length }}/{{ TAG_MAX }}</span>
              </div>
              <div v-if="tagError" class="md-form-hint is-error">{{ tagError }}</div>
            </el-form-item>
            <el-form-item label="封面图（≤ 2MB）">
              <!--
                上传与外链二选一，所以两个控件并排放而不是叠成两行：
                「用哪一张」是一个选择，并排比上下更能表达「二选一」。

                外链不预检能不能加载：跨域防盗链会让图在本地能显示、访客那边 404，
                提交前探测给出的是假的失败结论。
              -->
              <div class="md-kb-cover-row">
                <el-upload :auto-upload="false" :show-file-list="false" accept="image/*" :on-change="onCoverChange">
                  <el-button><MdIcon name="image" :size="14" />选择图片</el-button>
                </el-upload>
                <span class="md-kb-cover-or">或</span>
                <el-input
                  v-model="form.coverUrl"
                  class="md-kb-cover-url"
                  placeholder="图片外链，如 https://.../cover.png"
                  maxlength="1024"
                  clearable
                  @input="onCoverUrlInput"
                />
                <el-button
                  v-if="form.coverPreview"
                  class="md-kb-cover-clear"
                  title="移除封面"
                  @click="clearCover"
                >
                  <MdIcon name="close" :size="14" />
                </el-button>
              </div>
              <div v-if="form.coverPreview" class="md-kb-cover-tip">
                <img :src="form.coverPreview" alt="封面预览" class="md-kb-cover" @error="onCoverPreviewError" />
                <span v-if="coverBroken" class="md-form-hint is-error">
                  这张图加载不出来 —— 地址可能拼错了，或对方站点有防盗链（跨站防盗链会拦掉不来的图片）
                </span>
              </div>
            </el-form-item>
          </div>
        </section>

        <section class="md-kb-group">
          <h4 class="md-kb-group__title">
            分享设置
            <span class="md-kb-group__opt">可选</span>
          </h4>
          <div class="md-kb-cols">
            <el-form-item label="启用访问密码">
              <el-switch v-model="form.encrypted" />
            </el-form-item>
            <el-form-item label="有效期">
              <el-select v-model="form.expiresIn" style="width: 100%">
                <el-option label="永久有效" value="forever" />
                <el-option label="1 天" value="1d" />
                <el-option label="7 天" value="7d" />
                <el-option label="30 天" value="30d" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item v-if="form.encrypted" label="访问密码">
            <el-input v-model="form.password" maxlength="64" show-password placeholder="4-64 位" />
          </el-form-item>
        </section>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">
          <MdIcon name="close" :size="14" />取消
        </el-button>
        <el-button type="primary" @click="submitForm">
          <MdIcon name="check" :size="14" />保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 分享：与文档工作区共用一个面板（同一套有效期 / 密码 / 撤销，规范 §2.4） -->
    <ShareDialog
      v-model="shareVisible"
      :kb-slug="shareTarget?.slug || ''"
      :title="shareTarget?.name || ''"
      @changed="load"
    />

    <!--
      权限：读轴与写轴同屏，各一个控件（规范 §1.2 的「正交」只有在界面上分开才算落地）。
      名单只写进这个面板，因为它的生效前提就是档位为 members，两者分开呈现会被读成两件事。
    -->
    <el-dialog v-model="permVisible" :title="`权限 · ${permTarget?.name || ''}`" width="620px">
      <el-form label-position="top">
        <el-form-item label="可见范围（平台内谁能读）">
          <el-radio-group v-if="kbCan(permTarget, 'KB_SET_VISIBILITY')" v-model="perms.visibility">
            <el-radio-button value="org">本组织成员</el-radio-button>
            <el-radio-button value="private">仅维护名单</el-radio-button>
            <el-radio-button value="public">所有登录用户</el-radio-button>
          </el-radio-group>
          <span v-else class="md-ad-sub">{{ visibilityLabel(perms.visibility) }} · 只有创建者与组织管理员能改</span>
          <div class="md-form-hint">{{ visibilityHint(perms.visibility) }}</div>
        </el-form-item>

        <el-form-item v-if="canGovernKb" label="维护档位（谁能改）">
          <el-radio-group v-model="perms.maintainScope">
            <el-radio-button value="owner_only">仅我自己</el-radio-button>
            <el-radio-button value="members">维护名单</el-radio-button>
            <el-radio-button value="org_all">全组织可写</el-radio-button>
          </el-radio-group>
          <div class="md-form-hint">{{ maintainScopeHint(perms.maintainScope) }}</div>
        </el-form-item>
      </el-form>

      <template v-if="canGovernKb">
        <el-alert
          v-if="!rosterEffective"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          title="当前档位下，维护名单上的授权不产生任何权利；把档位改成「维护名单」后这些行才会生效。"
        />
        <div class="md-panel-head" style="padding-left: 0">
          <h2>维护名单</h2>
          <span class="md-ad-chip">{{ roster.length }} 人</span>
        </div>
        <p v-if="!roster.length" class="md-ad-sub">名单为空。members 档下没有任何人能写这个库。</p>
        <div v-for="row in roster" :key="row.userId" class="md-ad-row">
          <div class="md-ad-row__main">
            <div class="md-ad-row__title">
              <span>{{ row.displayName || row.username }}</span>
              <span class="md-ad-sub">@{{ row.username }}</span>
              <span class="md-ad-chip" :class="row.role === 'EDITOR' ? '' : 'md-ad-chip--private'">
                {{ row.role === 'EDITOR' ? '可写' : '只读' }}
              </span>
              <span v-if="!row.orgMember" class="md-ad-chip md-ad-chip--private" title="这人已不在组织里，这一行当前不产生权利">
                已离组
              </span>
            </div>
          </div>
          <div class="md-ad-row__actions">
            <el-button
              v-if="row.role === 'VIEWER'"
              size="small"
              link
              type="primary"
              :loading="rosterSaving"
              @click="grantRoster(row.userId, 'EDITOR')"
            >
              <MdIcon name="edit" :size="13" />改为可写
            </el-button>
            <el-button
              v-else
              size="small"
              link
              type="primary"
              :loading="rosterSaving"
              @click="grantRoster(row.userId, 'VIEWER')"
            >
              <MdIcon name="eye" :size="13" />改为只读
            </el-button>
            <el-button size="small" link type="danger" :loading="rosterSaving" @click="revokeRoster(row)">
              <MdIcon name="trash" :size="13" />撤销
            </el-button>
          </div>
        </div>

        <div class="md-ad-row" style="margin-top: 10px">
          <el-select v-model="grant.userId" filterable placeholder="选择组织成员" style="width: 220px">
            <el-option
              v-for="member in grantCandidates"
              :key="member.userId"
              :label="`${member.displayName || member.username}（@${member.username}）`"
              :value="member.userId"
            />
          </el-select>
          <el-select v-model="grant.role" style="width: 120px">
            <el-option label="可写（EDITOR）" value="EDITOR" />
            <el-option label="只读（VIEWER）" value="VIEWER" />
          </el-select>
          <el-button
            type="primary"
            plain
            :loading="rosterSaving"
            :disabled="!grant.userId"
            @click="grantRoster(grant.userId, grant.role)"
          >
            <MdIcon name="user-plus" :size="14" />授权
          </el-button>
        </div>
        <p v-if="!grantCandidates.length" class="md-copy-hint">
          组织里还没有可授权的人（名单不含你自己与 OWNER —— 他们的权限不经名单）。
        </p>
      </template>

      <template #footer>
        <el-button @click="permVisible = false">
          <MdIcon name="close" :size="14" />关闭
        </el-button>
        <el-button type="primary" :loading="permSaving" @click="savePerms">
          <MdIcon name="check" :size="14" />保存权限
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
