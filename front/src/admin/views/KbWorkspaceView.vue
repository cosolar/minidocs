<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Editor } from '@bytemd/vue-next'
import gfm from '@bytemd/plugin-gfm'
import highlight from '@bytemd/plugin-highlight'
import zhHans from 'bytemd/locales/zh_Hans.json'
import gfmZhHans from '@bytemd/plugin-gfm/locales/zh_Hans.json'
import 'bytemd/dist/index.css'
import DocTreeNode from '@/admin/components/DocTreeNode.vue'
import MdIcon from '@/admin/components/MdIcon.vue'
import ShareDialog from '@/admin/components/ShareDialog.vue'
import KbSettingsDialog from '@/admin/components/KbSettingsDialog.vue'
import GitCommitDialog from '@/admin/components/GitCommitDialog.vue'
import ImagePreview from '@/user/components/ImagePreview.vue'
import { draggingPath } from '@/admin/components/docDrag'
import { docApi, kbApi } from '@/admin/api'
import { TOKEN_KEY } from '@/admin/api/http'
import { toApiErrorInfo, type ApiErrorInfo } from '@/shared/api/http'
import { can } from '@/shared/api/caps'
import { portalKbUrl } from '@/shared/portal'
import { appHref } from '@/shared/appBase'
import KbGlyph from '@/shared/components/KbGlyph.vue'
import { maintainScopeHint, maintainScopeLabel, publishStatusChipClass, publishStatusHint, publishStatusLabel, visibilityChipClass, visibilityHint, visibilityLabel } from '@/shared/visibility'
import { enhancePreview } from '@/shared/enhanceMarkdown'
import { frontmatterStrip } from '@/shared/bytemdFrontmatter'
import { useIsMobile } from '@/admin/composables/useIsMobile'
import type { DocContentVO, DocNode, GitStatusVO, KbActionName, KbVO, LockVO } from '@/shared/api/types'

/** 编辑区展示形态：仅编辑 / 仅预览 */
type PaneMode = 'edit' | 'preview'
interface OutlineItem {
  key: string
  id: string
  text: string
  level: number
}

/**
 * 插件与后端渲染管线保持同一口径：flexmark 只注册了表格 / 删除线 / 任务列表 / 自动链接，
 * 因此这里也只挂 gfm + 代码高亮，避免编辑态预览出现阅读页渲染不出来的内容。
 * frontmatterStrip 只影响预览：frontmatter 是文档属性，不该出现在正文里。
 */
const plugins = [gfm({ locale: gfmZhHans }), highlight(), frontmatterStrip()]

/* ------------------------------------------------------------------ 编辑锁常量 */
/** 锁 TTL 由后端下发（60s），心跳取 TTL/3：连续两次续期失败也还没过期 */
const LOCK_HEARTBEAT_MS = 20000
/** 没锁的人每 10s 探一次，对方一保存完就恢复可编辑 */
const LOCK_POLL_MS = 10000
const CODE_LOCKED = 40902
const CODE_STALE_WRITE = 41200
/** 后端 FORBIDDEN：没有维护权，锁接口与保存接口给的是同一个码 */
const CODE_FORBIDDEN = 40300

const route = useRoute()
const router = useRouter()
/** 地址栏是组织与库的唯一来源：{@code /console/{org}/kbs/{slug}} */
const org = computed(() => String(route.params.org || ''))
const kbSlug = computed(() => String(route.params.slug || ''))

/**
 * 窄屏（≤900，与 admin.css 里工作区那组媒体查询一致）把左右两栏收成抽屉。
 *
 * <p>三栏并排在手机上每栏只剩百来像素：编辑区写不了字、目录看不出层级。所以窄屏默认
 * 只留中间的编辑区，目录与大纲改成浮层，由工具条上的按钮开关。</p>
 */
const isMobile = useIsMobile('(max-width: 900px)')

/* ------------------------------------------------------------------ 状态 */
const kb = ref<KbVO | null>(null)
const tree = ref<DocNode[]>([])
const loading = ref(true)
const treeLoading = ref(false)
const keyword = ref('')
const collapseAll = ref(false)
// 窄屏初始收起两栏：抽屉默认开着会直接盖住编辑区
const showTree = ref(!isMobile.value)
const showToc = ref(!isMobile.value)

const currentPath = ref('')
const docInfo = ref<DocContentVO | null>(null)
const content = ref('')
/** 最近一次「已落盘」的正文：用它与编辑区比对得出未保存状态 */
const savedText = ref('')
const dirty = ref(false)
const saving = ref(false)
const savedAt = ref('')
/** 最近一次落盘是自动保存还是手动点的：两者文案要分开，不然「自动保存」这四个字站不住 */
const savedAuto = ref(false)

/** 最近一次锁状态；null 表示还没拿到答复（正在获取，或获取被别的错误挡住） */
const lock = ref<LockVO | null>(null)
/** 获取锁失败且不是「他人持有」时的原因，例如本人没有写权 */
const lockDenied = ref('')
/** 与 {@link lockDenied} 同行的错误码：只有 403 才配那段「怎么拿到写权限」的指引 */
const lockDeniedCode = ref(0)
/**
 * 拿到锁那一刻的知识库 slug。
 *
 * <p>还锁时不能读 {@link kbSlug}：它取自路由参数，而组件卸载时路由早已换成下一个页面
 * （退回知识库列表后 {@code /console/{org}/kbs} 根本没有 slug 段），算出来是空串，
 * 请求会打到 {@code /kbs//lock} 上——被 Tomcat 归一成 {@code /kbs/lock} 之后正好撞上
 * 「删除知识库」的 {@code DELETE /{slug}}，拿 404 收场，锁也就没还掉。这里存一份快照。</p>
 */
const lockedKbSlug = ref('')

const editorRef = ref<HTMLDivElement | null>(null)
/**
 * 编辑区展示形态，默认「预览」。
 *
 * <p>工作区多数时候是「先读一眼」，改是显式动作；每打开一篇都回到这一档，
 * 免得上一篇停在编辑态，换一篇后又对着 Markdown 源码看。</p>
 */
const paneMode = ref<PaneMode>('preview')
const outline = ref<OutlineItem[]>([])
const activeOutlineKey = ref('')
/**
 * 大纲手风琴的手工展开状态（key → 是否展开）。
 *
 * <p>默认只展开「当前正在读的那条标题链」，这里存读者点过箭头的覆盖值，优先级更高 ——
 * 点了就该照做，否则「点收起却没反应」比收错了更让人困惑。</p>
 */
const outlineOverrides = reactive(new Map<string, boolean>())
const words = ref(0)
const lines = ref(0)

const importInput = ref<HTMLInputElement | null>(null)
/** 选目录用的另一个 input：webkitdirectory 与 multiple 是两个互斥的取数口径，只能各留一个 */
const importDirInput = ref<HTMLInputElement | null>(null)

/**
 * 锁倒计时的走字时刻。
 *
 * <p>只做「现在几点」这一件事的响应式来源：锁的剩余时间由 {@code expiresAt} 减出来，
 * 不推进时刻的话横幅会一直停在打开文档那一刻的秒数上，看起来像卡住了。</p>
 */
const now = ref(Date.now())
let autoSaveTimer: number | undefined
let heartbeatTimer: number | undefined
let pollTimer: number | undefined
let tickTimer: number | undefined
/** 组件是否还活着：卸载后不能再安排锁定时器 */
let alive = true
let outlineTimer: number | undefined
let spyTimer: number | undefined
let readyTimer: number | undefined
let previewBound: HTMLElement | null = null

const paneOptions: Array<{ key: PaneMode; label: string; icon: string }> = [
  { key: 'preview', label: '预览', icon: 'eye' },
  { key: 'edit', label: '编辑', icon: 'edit' }
]

/* ------------------------------------------------------------------ 派生 */
const flatDocs = computed(() => {
  const result: DocNode[] = []
  const walk = (nodes: DocNode[]) => {
    nodes.forEach((node) => {
      if (node.type === 'doc') result.push(node)
      else walk(node.children || [])
    })
  }
  walk(tree.value)
  return result
})

/** 按关键字裁剪目录树（保留命中节点及其祖先） */
const visibleTree = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return tree.value
  const prune = (nodes: DocNode[]): DocNode[] =>
    nodes
      .map((node) => {
        if (node.type === 'doc') {
          return node.name.toLowerCase().includes(key) ? node : null
        }
        const children = prune(node.children || [])
        return children.length > 0 || node.name.toLowerCase().includes(key) ? { ...node, children } : null
      })
      .filter((node): node is DocNode => node !== null)
  return prune(tree.value)
})

const visibilityText = computed(() => (kb.value ? visibilityLabel(kb.value.visibility) : ''))
const crumbs = computed(() => (currentPath.value ? currentPath.value.split('/') : []))
const displayTitle = computed(() => docInfo.value?.title || crumbs.value[crumbs.value.length - 1] || '')
const authorLabel = computed(() => frontmatterValue(content.value, 'author') || kb.value?.ownerName || '—')
// 桌面编辑态用 bytemd 自带的大纲；窄屏把它藏掉、改用自己的抽屉，所以两种形态都放行
const tocVisible = computed(() => showToc.value && (isMobile.value || paneMode.value !== 'edit'))
const paneClass = computed(() => `is-${paneMode.value}`)
/**
 * 保存状态文案。
 *
 * <p>自动保存与手动保存分开说：只写「已保存」，用户没法解释「我没点过保存，它怎么自己保存了」。</p>
 */
const saveStateText = computed(() => {
  if (saving.value) return '保存中…'
  if (dirty.value) return '未保存'
  if (!savedAt.value) return ''
  return `${savedAuto.value ? '自动保存' : '已保存'} ${savedAt.value}`
})

/* ------------------------------------------------------------------ 编辑锁派生 */
const haveLock = computed(() => lock.value?.mine === true)
/**
 * 这个库上我能不能做某件事 —— 只看后端下发的动作向量（规范 §2.6），不在前端重抄档位规则。
 *
 * <p>空向量按「本次没算」放行（与列表页同一口径，判定在 {@code shared/api/caps}）：漏给值是
 * 后端的 bug，代价应当是多显示一个按钮并被后端 403 挡下，而不是把编辑功能整片藏起来。</p>
 */
const kbCan = (action: KbActionName) => can(kb.value?.myPermissions, action)
/** 写轴：编辑、新建、保存都看这一条（DOC_WRITE 一个动作覆盖整条维护通道） */
const canWriteKb = computed(() => kbCan('DOC_WRITE'))
/** 没有写权限（不是「锁在别人手里」）：这一条不需要请求锁就成立。 */
const writeDenied = computed(() => !!currentPath.value && !canWriteKb.value)
/**
 * 编辑区是否只读。
 *
 * <p>预览是只读行为，本身就不该持锁，因此非编辑模式恒为只读：编辑器在预览态是隐藏的，
 * 这一条同时兜住「刚点进编辑、锁请求还没回来」的瞬间，避免那一刻 CodeMirror 短暂可写。</p>
 *
 * <p>编辑模式下锁状态未知（刚打开、正在获取）时**不**只读：那一瞬间闪一层灰底，
 * 会让人以为文档被锁住，而实际只是请求还没回来。</p>
 */
const readOnly = computed(
  () =>
    !!currentPath.value &&
    (paneMode.value !== 'edit' ||
      writeDenied.value ||
      !!lockDenied.value ||
      (lock.value !== null && lock.value.mine !== true))
)
const lockText = computed(() => {
  if (writeDenied.value) return '你在这个库上是只读成员'
  if (lockDenied.value) return lockDenied.value
  const state = lock.value
  if (!state) return '正在获取编辑锁…'
  const left = lockSecondsLeft(state)
  if (state.mine) return `已获得编辑锁${left ? ` · 剩余约 ${left}s（自动续期）` : ''}`
  const who = state.nickname || state.holder || '他人'
  return `${who} 正在编辑该文档${left ? ` · 约 ${left}s 后可接管` : ''}`
})
/**
 * 只读时的下一步（U2：说清「谁 / 为什么 / 怎么办」，不能只说「失败」）。
 *
 * <p>两种只读共用这段：一是权限向量里就没有 DOC_WRITE（进页面即知），二是硬去要锁被后端 403
 * 挡住（权限向量没给、但服务端判你没有写权）。网络错误与服务端 5xx 不给 —— 对那种原因讲
 * 「去找创建者要权限」是把用户的注意力往错方向引。档位与创建者都取自 {@code kb} 详情。</p>
 */
const lockHint = computed(() => {
  const forbidden = lockDeniedCode.value === CODE_FORBIDDEN
  if ((!writeDenied.value && !forbidden) || !kb.value) return ''
  const who = kb.value.ownerName ? `创建者 ${kb.value.ownerName}` : '创建者'
  return (
    `当前维护档位为「${maintainScopeLabel(kb.value.maintainScope)}」，你不在可写范围内。` +
    `想让某人可写，请由${who}或组织管理员在该库的「权限」里把对方加入维护名单，或把档位改为「全组织可写」。`
  )
})

const kbMetaRows = computed(() => [
  { label: '文档数量', value: `${kb.value?.docCount ?? 0}` },
  // 两条轴分开列，不写「权限状态」这种一句话糊过去的行：读与写在这里本来就是两回事
  { label: '谁能读', value: visibilityText.value },
  { label: '谁能写', value: maintainScopeLabel(kb.value?.maintainScope) },
  { label: '创建人', value: kb.value?.ownerName || '—' },
  { label: '创建时间', value: formatDate(kb.value?.createdAt) }
])

/* ------------------------------------------------------------------ 工具 */
function formatDate(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => `${n}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function formatClock(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => `${n}`.padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 锁还剩几秒。用 {@code expiresAt} 而不是 {@code ttlSeconds}：后者是下发那一刻的余量，
 * 页面停一会儿就成了假数字。{@code now} 每秒推一次，倒计时才会走字。
 */
function lockSecondsLeft(state: LockVO | null): number {
  if (!state?.expiresAt) return 0
  const left = Math.round((new Date(state.expiresAt).getTime() - now.value) / 1000)
  return left > 0 ? left : 0
}

/** 读取正文头部 YAML frontmatter 的扁平字段（与后端 FrontMatterParser 的约定一致） */
function frontmatterValue(text: string, key: string) {
  if (!text || !text.startsWith('---')) return ''
  const end = text.indexOf('\n---', 3)
  if (end === -1) return ''
  const block = text.slice(3, end)
  const matched = block.match(new RegExp(`^\\s*${key}\\s*:\\s*(.+)$`, 'm'))
  return matched ? matched[1].trim().replace(/^['"]|['"]$/g, '') : ''
}

/** 与后端 MarkdownService.countWords 口径一致：CJK 逐字计，拉丁/数字按词计 */
function countWords(text: string) {
  let count = 0
  let inLatin = false
  for (const char of text) {
    const code = char.codePointAt(0) ?? 0
    const isCjk =
      (code >= 0x4e00 && code <= 0x9fff) ||
      (code >= 0x3400 && code <= 0x4dbf) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0x3040 && code <= 0x30ff) ||
      (code >= 0xac00 && code <= 0xd7af)
    if (isCjk) {
      count += 1
      inLatin = false
    } else if (/[0-9A-Za-z]/.test(char)) {
      if (!inLatin) count += 1
      inLatin = true
    } else {
      inLatin = false
    }
  }
  return count
}

function updateStats(text: string) {
  const value = text || ''
  words.value = countWords(value)
  lines.value = value ? value.split('\n').length : 0
}

/* ------------------------------------------------------------------ 数据加载 */
async function loadKb() {
  kb.value = await kbApi.detail(kbSlug.value)
}

async function loadTree() {
  treeLoading.value = true
  try {
    tree.value = await docApi.tree(kbSlug.value)
  } finally {
    treeLoading.value = false
  }
}

async function openDoc(path: string, discard = false, force = false) {
  if (!path) return
  const sameDoc = path === currentPath.value
  // force：同一篇文档也要重取正文（拉取远端更新后用），此时不能因为「还是这一篇」就提前返回
  if (sameDoc && !dirty.value && !force) return
  if (dirty.value && !discard) {
    try {
      await ElMessageBox.confirm('当前文档有未保存的修改，是否保存？', '未保存的修改', {
        confirmButtonText: '保存并切换',
        cancelButtonText: '放弃修改',
        distinguishCancelAndClose: true,
        type: 'warning'
      })
      // 预览模式不持锁（也可能锁已被他人接管）：要保存先把锁拿回来，拿不到就留在本篇，
      // 绝不能让「保存并切换」在无锁时静默失败后继续切走、把未落盘的改动丢掉
      if (!haveLock.value && !(await acquireLock(currentPath.value))) return
      await save(true)
    } catch (action) {
      if (action === 'close') return
    }
  }
  // 换一篇就回到预览：编辑是显式动作，不该跟着上一篇的选择漂到下一篇。
  // 同一篇的重载（拉取后刷新正文）不动视图模式，否则用户正编辑着就被弹回预览。
  // 这一步必须排在还锁之前：syncLockTimers 只在编辑模式下安排心跳/轮询，模式不先切走，
  // 还锁瞬间会起一个轮询，10s 后又在预览态把锁抢回来。
  if (!sameDoc) paneMode.value = 'preview'
  // 一锁一文档：换页面前先把上一页的锁还掉，否则别人要等 60s TTL 才进得来
  await releaseLock()
  lock.value = null
  lockDenied.value = ''
  lockDeniedCode.value = 0
  // 上一篇遗留的自动保存计时器得掐掉：等它到点，dirty 已经归到新文档头上，会存错地方
  window.clearTimeout(autoSaveTimer)
  try {
    const doc = await docApi.read(kbSlug.value, path)
    docInfo.value = doc
    currentPath.value = doc.path
    // 窄屏选完文档就把抽屉收起来，否则用户看不到自己刚点开的正文
    if (isMobile.value) showTree.value = false
    content.value = doc.content
    savedText.value = doc.content
    activeOutlineKey.value = ''
    outline.value = []
    updateStats(doc.content)
    dirty.value = false
    savedAt.value = ''
    savedAuto.value = false
    await nextTick()
    bindPreviewHost()
    refreshPreview()
    syncRoutePath(doc.path)
    // 预览是只读行为：不抢锁、不续期、不轮询，同事不会被一篇「只是在看」的文档挡在外面。
    // 只有显式进入编辑模式才要锁；没有写权限也不去要——那条 403 只会把「你进不来」变成
    // 一次请求失败，横幅上该有的「只读成员 + 怎么拿到写权」不请求也能讲清楚。
    if (paneMode.value === 'edit' && canWriteKb.value) await acquireLock(doc.path)
  } catch {
    /* 拦截器已提示 */
  }
}

/**
 * 进入编辑模式后确保锁在手里。
 *
 * <p>有写权才发请求；锁被他人持有时后端回 409，{@link acquireLock} 会把横幅切成
 * 「谁在写 + 轮询等待接管」；无写权时不请求，{@code writeDenied} 已足够说明问题。</p>
 */
async function ensureLockForEdit() {
  if (!currentPath.value || !canWriteKb.value || haveLock.value) return
  await acquireLock(currentPath.value)
}

/** 把当前文档写进地址栏，便于刷新 / 分享定位（后端渲染出的文档链接同样用 ?path=） */
function syncRoutePath(path: string) {
  if (route.query.path === path) return
  void router.replace({
    name: 'kb-workspace',
    params: { org: org.value, slug: kbSlug.value },
    query: { path }
  })
}

/* ------------------------------------------------------------------ 编辑区 */
/** 停手多久算「这一段写完了」：太短会在敲到一半时落盘，太长又让自动保存名不副实 */
const AUTO_SAVE_IDLE_MS = 2500

/**
 * 打字停手后再落盘。
 *
 * <p>不用定时轮询：轮询会在「正敲到一半」的时刻保存，既费请求，也可能把半截句子写进磁盘。
 * 每次输入都把计时器推倒重来，直到用户停手满 {@code AUTO_SAVE_IDLE_MS} 才真正保存。</p>
 */
function scheduleAutoSave() {
  window.clearTimeout(autoSaveTimer)
  if (readOnly.value || !currentPath.value || !dirty.value) return
  autoSaveTimer = window.setTimeout(() => {
    if (dirty.value && currentPath.value) void save(true)
  }, AUTO_SAVE_IDLE_MS)
}

function onContentChange(value: string) {
  const text = value ?? ''
  content.value = text
  dirty.value = text !== savedText.value
  updateStats(text)
  scheduleOutline()
  scheduleAutoSave()
}

async function uploadImages(files: File[]) {
  const results: Array<{ url: string; alt?: string; title?: string }> = []
  for (const file of files) {
    try {
      const res = await docApi.uploadImage(kbSlug.value, file)
      results.push({ url: res.path, alt: file.name, title: file.name })
    } catch {
      /* 拦截器已提示 */
    }
  }
  return results
}

/**
 * 唤醒 CodeMirror。
 *
 * <p>默认落在预览态，此时编辑区是 {@code display:none}，CodeMirror 初始化量到的全是零尺寸，
 * 视口里一行都不渲染（所以左栏看着是整片空白，而拉一下窗口它又会自己好）。切到编辑态时
 * 必须让它重新量一次。</p>
 */
function refreshEditor() {
  const host = editorRef.value?.querySelector<HTMLElement & { CodeMirror?: { refresh?: () => void } }>(
    '.CodeMirror'
  )
  host?.CodeMirror?.refresh?.()
}

async function setPaneMode(next: PaneMode) {
  if (next === paneMode.value) return
  paneMode.value = next
  if (next === 'edit') {
    // 显式进入编辑才拿锁：先让 CodeMirror 在可见状态下重新量尺寸，锁请求与之并行即可
    await nextTick(() => {
      refreshPreview()
      refreshEditor()
    })
    await ensureLockForEdit()
    return
  }
  // 回到预览立即还锁：读不需要锁，别人不必等这一篇的 60s TTL，本地也不再续期/轮询
  await releaseLock()
  // releaseLock 只在「锁在我手里」时清状态；409（他人持有）/403（被拒）会早退，这里一并清掉
  lock.value = null
  lockDenied.value = ''
  lockDeniedCode.value = 0
  syncLockTimers()
  await nextTick(refreshPreview)
}

/* ------------------------------------------------------------------ 预览区后处理 */
function previewHost(): HTMLElement | null {
  return editorRef.value?.querySelector<HTMLElement>('.bytemd-preview') || null
}

function markdownBody(): HTMLElement | null {
  const host = previewHost()
  return host?.querySelector<HTMLElement>('.markdown-body') || host
}

function collectHeadings(): HTMLElement[] {
  const body = markdownBody()
  if (!body) return []
  return Array.from(body.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6'))
}

/**
 * bytemd 的预览不生成标题锚点，也不认识知识库内的相对图片路径，
 * 这里在渲染完成后统一补 id、补全图片地址（顺带带上资产接口鉴权路径）。
 */
function postProcessPreview() {
  collectHeadings().forEach((heading, index) => {
    const id = `md-h-${index}`
    if (heading.id !== id) heading.id = id
  })
  const host = previewHost()
  if (!host) return
  host.querySelectorAll<HTMLImageElement>('img[src]').forEach((img) => {
    const src = (img.getAttribute('src') || '').trim()
    if (!src || src.startsWith('/') || src.startsWith('data:') || /^([a-z][a-z0-9+.-]*:)?\/\//i.test(src)) return
    img.setAttribute('src', kbApi.assetUrl(kbSlug.value, src))
  })
  // bytemd 预览只挂了 gfm + 代码高亮，```mermaid 围栏得借阅读页那套图表增强才出图，
  // 图片与 SVG 的灯箱也一并在这里挂上
  void enhancePreview(markdownBody())
}

/** 预览区（含标题 id、图片地址）与大纲一起刷新 */
function refreshPreview() {
  window.clearTimeout(readyTimer)
  const run = () => {
    postProcessPreview()
    refreshOutline()
  }
  void nextTick(run)
  // Viewer 是异步渲染管线，稍后再兜一次，避免拿到上一次的 DOM
  readyTimer = window.setTimeout(run, 260)
}

function bindPreviewHost() {
  const wrap = editorRef.value
  if (!wrap || wrap === previewBound) return
  unbindPreviewHost()
  previewBound = wrap
  // 滚动事件不冒泡，用捕获阶段统一监听编辑区内部的滚动
  wrap.addEventListener('scroll', onPreviewScroll, true)
  wrap.addEventListener('click', onPreviewClick, true)
}

function unbindPreviewHost() {
  if (!previewBound) return
  previewBound.removeEventListener('scroll', onPreviewScroll, true)
  previewBound.removeEventListener('click', onPreviewClick, true)
  previewBound = null
}

/* ------------------------------------------------------------------ 大纲 */
function scheduleOutline() {
  window.clearTimeout(outlineTimer)
  outlineTimer = window.setTimeout(() => {
    postProcessPreview()
    refreshOutline()
  }, 400)
}

function refreshOutline() {
  if (!currentPath.value) {
    outline.value = []
    return
  }
  const items = collectHeadings()
    .map((heading, index) => ({
      key: `${index}`,
      id: `md-h-${index}`,
      level: Number(heading.tagName.substring(1)),
      text: (heading.textContent || '').replace(/\s+/g, ' ').trim()
    }))
    .filter((item) => item.text)
  outline.value = items
  if (!items.some((item) => item.key === activeOutlineKey.value)) {
    activeOutlineKey.value = items[0]?.key || ''
  }
}

/** 哪些标题底下还有子标题：只有它们才配一个展开箭头 */
const outlineParentKeys = computed(() => {
  const parents = new Set<string>()
  const items = outline.value
  for (let i = 0; i < items.length - 1; i += 1) {
    if (items[i + 1].level > items[i].level) parents.add(items[i].key)
  }
  return parents
})

/** 从根到当前阅读标题的 key 链：只有这条链默认展开 */
const activeOutlineChain = computed(() => {
  const chain = new Set<string>()
  const items = outline.value
  const index = items.findIndex((item) => item.key === activeOutlineKey.value)
  if (index < 0) return chain
  chain.add(items[index].key)
  let level = items[index].level
  for (let i = index - 1; i >= 0 && level > 1; i -= 1) {
    if (items[i].level < level) {
      chain.add(items[i].key)
      level = items[i].level
    }
  }
  return chain
})

function isOutlineExpanded(key: string) {
  return outlineOverrides.get(key) ?? activeOutlineChain.value.has(key)
}

function toggleOutline(key: string) {
  outlineOverrides.set(key, !isOutlineExpanded(key))
}

/**
 * 折叠后真正要渲染的行。
 *
 * <p>大纲本身是「按 level 排好的扁平数组」，所以这里不建树：一遍扫描，遇到收起的分支就把
 * 它后面所有更深的标题跳过，直到回到同级或更浅为止 —— 比递归组件少一层抽象。</p>
 */
const visibleOutline = computed(() => {
  const rows: OutlineItem[] = []
  let hiddenBelow = Number.POSITIVE_INFINITY
  for (const item of outline.value) {
    if (item.level > hiddenBelow) continue
    hiddenBelow = Number.POSITIVE_INFINITY
    rows.push(item)
    if (outlineParentKeys.value.has(item.key) && !isOutlineExpanded(item.key)) hiddenBelow = item.level
  }
  return rows
})

/**
 * 大纲的「结构指纹」：标题增删改都会变。
 *
 * <p>刷新大纲是每次改动后都会跑的（防抖 400ms），所以不能拿数组身份当「换文档」的信号。
 * 指纹兜住这件事：结构一变就丢掉手工展开状态，回到默认的「只展开当前阅读链」；
 * 只改正文段落时指纹不变，读者摊开的枝不会莫名其妙合上。</p>
 */
const outlineSignature = computed(() => outline.value.map((item) => `${item.level}:${item.text}`).join('\n'))

watch(outlineSignature, () => outlineOverrides.clear())

function scrollToHeading(item: OutlineItem) {
  activeOutlineKey.value = item.key
  const target = collectHeadings()[Number(item.key)] || document.getElementById(item.id)
  target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function onPreviewScroll() {
  if (spyTimer) return
  spyTimer = window.setTimeout(() => {
    spyTimer = undefined
    syncActiveHeading()
  }, 120)
}

/** 滚动联动：取最后一条越过预览区顶部的标题作为当前章节 */
function syncActiveHeading() {
  const host = previewHost()
  if (!host || !outline.value.length) return
  const headings = collectHeadings()
  if (!headings.length) return
  const threshold = host.getBoundingClientRect().top + 96
  let current = 0
  headings.forEach((heading, index) => {
    if (heading.getBoundingClientRect().top <= threshold) current = index
  })
  if (`${current}` !== activeOutlineKey.value) activeOutlineKey.value = `${current}`
}

/** 预览区里的站内 .md 链接直接在工作区切换文档，避免跳出到无效地址 */
function onPreviewClick(event: MouseEvent) {
  const anchor = (event.target as HTMLElement | null)?.closest?.('a')
  if (!anchor) return
  const href = anchor.getAttribute('href') || ''
  if (!href || href.startsWith('#') || href.startsWith('/') || /^[a-z][a-z0-9+.-]*:/i.test(href)) return
  const [filePart] = href.split('#')
  if (!/\.(md|markdown)$/i.test(filePart)) return
  event.preventDefault()
  event.stopPropagation()
  void openDoc(resolveRelativePath(decodeURIComponent(filePart)))
}

function resolveRelativePath(href: string) {
  const base = currentPath.value.includes('/')
    ? currentPath.value.slice(0, currentPath.value.lastIndexOf('/'))
    : ''
  const stack: string[] = []
  ;(base ? `${base}/${href}` : href).split('/').forEach((segment) => {
    if (!segment || segment === '.') return
    if (segment === '..') stack.pop()
    else stack.push(segment)
  })
  return stack.join('/')
}

/* ------------------------------------------------------------------ 编辑锁（§4.3 / F8） */

/**
 * 这一版 @bytemd/vue-next 没有 readOnly 属性，editorConfig 也只在初始化时生效，
 * 因此直接取 CodeMirror 实例改选项。bytemd 1.x 内嵌 CodeMirror 5，实例挂在 .CodeMirror 元素上。
 */
function setEditorReadOnly(next: boolean) {
  const host = editorRef.value?.querySelector<HTMLElement & { CodeMirror?: { setOption?: (k: string, v: unknown) => void } }>(
    '.CodeMirror'
  )
  host?.CodeMirror?.setOption?.('readOnly', next ? 'nocursor' : false)
}

function applyLock(state: LockVO | null, reason = '', reasonCode = 0) {
  const lost = haveLock.value && !!state && state.mine !== true
  lock.value = state
  lockDenied.value = state ? '' : reason
  lockDeniedCode.value = state ? 0 : reasonCode
  syncLockTimers()
  if (lost) ElMessage.warning('编辑锁已不在手里，已转为只读')
}

/** 获取或续期，幂等。quiet 给心跳用：后台续期失败不该每 20s 弹一次吐司。 */
async function acquireLock(path: string, quiet = false) {
  try {
    const slug = kbSlug.value
    applyLock(await docApi.acquireLock(slug, path))
    lockedKbSlug.value = slug
    return true
  } catch (error) {
    const info = toApiErrorInfo(error)
    if (info.code === CODE_LOCKED) {
      // 409 的 data 就是持有者的锁状态：横幅要显示的是「谁在写」，不是「请求失败了」
      applyLock({ path, locked: true, ttlSeconds: 0, mine: false, ...(info.data as LockVO | undefined) })
    } else if (!quiet || !lock.value) {
      /*
       * 403 单独认出来：这一条不是「稍后再试」的失败，而是「你没有写权限」，
       * 横幅要换成 who/why/how 的说明，且不能再让轮询每 10s 去撞一次墙。
       */
      applyLock(null, info.message, info.code)
      if (!quiet) ElMessage.error(info.message)
    }
    return false
  }
}

async function pollLock() {
  if (!currentPath.value) return
  try {
    const state = await docApi.lockState(kbSlug.value, currentPath.value)
    if (!state.locked) {
      await acquireLock(currentPath.value, true)
      return
    }
    // 换人了（管理员强制解锁后第三人抢到）也要如实反映到横幅上
    if (state.holderUserId !== lock.value?.holderUserId) applyLock(state)
  } catch {
    /* 轮询失败维持现状：网络抖动不该把编辑器变灰 */
  }
}

/** keepalive 给页面卸载用：sendBeacon 带不上 Authorization 头，keepalive fetch 能在卸载后发完 */
async function releaseLock(keepalive = false) {
  const path = currentPath.value
  // 库 slug 只认拿到锁时存下的快照：卸载路径上 kbSlug 已经跟着路由切走了
  const slug = lockedKbSlug.value
  if (!path || !slug || !haveLock.value) return
  lock.value = null
  lockedKbSlug.value = ''
  syncLockTimers()
  if (keepalive) {
    const token = localStorage.getItem(TOKEN_KEY)
    /*
     * 文档销毁时浏览器会把这条请求标记成 net::ERR_ABORTED（请求该不该发出去由 keepalive 兜底），
     * 没有 catch 就会变成一条 unhandled rejection 刷在控制台里，而这里对失败本就无能为力。
     */
    void fetch(docApi.lockUrl(slug, path), {
      method: 'DELETE',
      keepalive: true,
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    }).catch(() => {
      /* 静默：还锁失败由 TTL 收尾 */
    })
    return
  }
  try {
    await docApi.releaseLock(slug, path)
  } catch {
    /* 静默：没还得掉锁由 TTL 收尾，切换文档不该为此弹错误 */
  }
}

/**
 * 心跳与轮询不会同时存在：有锁就续期，没锁才等它空出来。
 *
 * <p>两者都只属于编辑模式：预览不持锁，起心跳是给一篇「在看」的文档无谓续命，
 * 起轮询更糟——{@link pollLock} 探到锁空出会直接抢回来，等于预览又把锁拿了。
 */
function syncLockTimers() {
  window.clearInterval(heartbeatTimer)
  window.clearInterval(pollTimer)
  window.clearInterval(tickTimer)
  heartbeatTimer = undefined
  pollTimer = undefined
  tickTimer = undefined
  // 卸载后 currentPath 还在，不还锁就会留下一个每 10s 打一次接口的后台定时器
  if (!alive || !currentPath.value || paneMode.value !== 'edit') return
  if (lock.value?.expiresAt) {
    // 只有横幅上要显示倒计时时才走字，空闲页面不该白占一个 1s 定时器
    tickTimer = window.setInterval(() => {
      now.value = Date.now()
    }, 1000)
  }
  if (haveLock.value) {
    heartbeatTimer = window.setInterval(() => void acquireLock(currentPath.value, true), LOCK_HEARTBEAT_MS)
  } else if (!lockDenied.value && canWriteKb.value) {
    // 没有写权限时不轮询：锁迟早会空出来，但抢到也只是让人反复看见一个编辑不了的编辑器
    pollTimer = window.setInterval(() => void pollLock(), LOCK_POLL_MS)
  }
}

/* ------------------------------------------------------------------ 保存 */
async function save(silent = false) {
  if (!currentPath.value) return
  if (!haveLock.value) {
    if (!silent) ElMessage.warning(lockText.value)
    return
  }
  const path = currentPath.value
  const value = content.value
  saving.value = true
  try {
    await docApi.save(kbSlug.value, {
      path,
      content: value,
      baseSize: docInfo.value?.size,
      baseMtime: docInfo.value?.modifiedAt
    })
    savedText.value = value
    /*
     * 按「当前正文」重算，而不是无条件清 dirty：写请求往返期间用户可能又敲了几个字，
     * 那几个字既没进这次请求，也不该被这次成功连坐成「已保存」。留着 dirty 还会顺带
     * 让下一次自动保存重新排队（dirty 归零的话计时器到点也是空跑，改动就悄无声息地悬着了）。
     */
    dirty.value = content.value !== savedText.value
    savedAt.value = formatClock(new Date().toISOString())
    savedAuto.value = silent
    if (dirty.value) scheduleAutoSave()
    if (!silent) ElMessage.success('已保存')
    await Promise.all([loadTree(), loadKb()])
    await adoptBaseline(path)
    refreshPreview()
  } catch (error) {
    await onSaveError(error, silent)
  } finally {
    saving.value = false
  }
}

/**
 * 保存成功后必须换基线：刚写进去的内容让手里那份 size/modifiedAt 作废，不换的话下一次保存会被
 * 自己的 412 挡在门外。目录树带的就是同一套磁盘读数，省一次读请求。
 */
async function adoptBaseline(path: string) {
  const node = flatDocs.value.find((item) => item.path === path)
  if (node && docInfo.value) {
    docInfo.value = { ...docInfo.value, size: node.size, modifiedAt: node.modifiedAt }
    return
  }
  try {
    docInfo.value = await docApi.read(kbSlug.value, path)
  } catch {
    /* 拿不到基线就只能下次撞 412，由冲突弹窗接手 */
  }
}

async function onSaveError(error: unknown, silent: boolean) {
  const info: ApiErrorInfo = toApiErrorInfo(error)
  if (info.code === CODE_LOCKED) {
    applyLock({ path: currentPath.value, locked: true, ttlSeconds: 0, mine: false, ...(info.data as LockVO | undefined) })
    if (!silent) ElMessage.warning(info.message)
    return
  }
  if (info.code === CODE_STALE_WRITE) {
    await resolveConflict(silent)
    return
  }
  if (!silent) ElMessage.error(info.message)
}

/**
 * 412：磁盘上的内容已经不是读到那份了（锁过期后仍在编辑，或有人绕过应用直接改盘）。
 * 不自动合并（§4.3）—— 把我们的版本送进剪贴板再载最新版，两版都不丢，也不必新存储。
 */
async function resolveConflict(silent: boolean) {
  if (silent) {
    ElMessage.warning('文档已被他人修改，自动保存已跳过')
    return
  }
  try {
    await ElMessageBox.confirm(
      '这篇文档在你编辑期间被改动过。重新载入会拿到最新版；你正在写的内容会先复制到剪贴板，两边都不会丢。',
      '保存基线已过期',
      { confirmButtonText: '复制我的并载入最新', cancelButtonText: '继续编辑', type: 'warning' }
    )
  } catch {
    return
  }
  await copyText(content.value, '我的版本已复制到剪贴板')
  await openDoc(currentPath.value, true)
}

/* ------------------------------------------------------------------ 目录操作 */
function promptInput(title: string, message: string, preset: string): Promise<string | null> {
  return ElMessageBox.prompt(message, title, {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputValue: preset,
    inputValidator: (value: string) => (value && value.trim() ? true : '名称不能为空')
  })
    .then(({ value }) => value.trim())
    .catch(() => null)
}

async function createDoc(parentDir = '') {
  const name = await promptInput('新建文档', '请输入文档名称（可省略 .md）', '新文档')
  if (!name) return
  const path = parentDir ? `${parentDir}/${name}` : name
  const result = await docApi.create(kbSlug.value, { path })
  ElMessage.success('文档已创建')
  await loadTree()
  await openDoc(result.path)
}

async function createDir(parentDir = '') {
  const name = await promptInput('新建目录', '请输入目录名称', '新目录')
  if (!name) return
  const path = parentDir ? `${parentDir}/${name}` : name
  await docApi.createDir(kbSlug.value, path)
  ElMessage.success('目录已创建')
  await loadTree()
}

async function renameNode(node: DocNode) {
  const name = await promptInput('重命名', '请输入新的名称', node.name)
  if (!name) return
  const result = await docApi.rename(kbSlug.value, { path: node.path, name })
  ElMessage.success('已重命名')
  await loadTree()
  if (currentPath.value === node.path) {
    currentPath.value = result.path
    void syncRoutePath(result.path)
  }
}

async function moveNode(node: DocNode) {
  const dirs = ['', ...collectDirs(tree.value)]
  const targetDir = await ElMessageBox.prompt(
    `输入目标目录（相对知识库根，留空表示根目录）：\n可选：${dirs.slice(0, 12).join(' / ') || '根目录'}`,
    '移动到…',
    { confirmButtonText: '移动', cancelButtonText: '取消', inputValue: '' }
  )
    .then(({ value }) => value.trim())
    .catch(() => null)
  if (targetDir === null) return
  const result = await docApi.move(kbSlug.value, { path: node.path, targetDir })
  ElMessage.success('已移动')
  await loadTree()
  if (currentPath.value === node.path) {
    currentPath.value = result.path
    void syncRoutePath(result.path)
  }
}

async function deleteNode(node: DocNode) {
  await ElMessageBox.confirm(`确认删除「${node.name}」？目录将连同其中的文档一并删除。`, '删除确认', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  })
  await docApi.remove(kbSlug.value, node.path)
  ElMessage.success('已删除')
  if (currentPath.value === node.path || currentPath.value.startsWith(`${node.path}/`)) {
    // 文档已经没了：继续心跳等于每 20s 对不存在的路径打一次接口，必须先摘锁再清页面
    await releaseLock()
    currentPath.value = ''
    docInfo.value = null
    content.value = ''
    savedText.value = ''
    outline.value = []
    lock.value = null
    lockDenied.value = ''
    syncLockTimers()
    updateStats('')
  }
  await loadTree()
  await loadKb()
}

function collectDirs(nodes: DocNode[]): string[] {
  const result: string[] = []
  const walk = (list: DocNode[]) => {
    list.forEach((node) => {
      if (node.type === 'dir') {
        result.push(node.path)
        walk(node.children || [])
      }
    })
  }
  walk(nodes)
  return result
}

async function onTreeAction(payload: { action: string; node: DocNode }) {
  const { action, node } = payload
  if (action === 'new-doc') return createDoc(node.path)
  if (action === 'new-dir') return createDir(node.path)
  if (action === 'rename') return renameNode(node)
  if (action === 'move') return moveNode(node)
  if (action === 'delete') return deleteNode(node)
  if (action === 'download') {
    window.open(docApi.downloadUrl(kbSlug.value, node.path), '_blank')
    return
  }
  if (action === 'share') openSharePanel(node.path)
}

function toggleCollapseAll() {
  collapseAll.value = !collapseAll.value
}

/* ------------------------------------------------------------------ 拖拽移动 */
/** 拖到树的空白处＝移到根目录 */
const rootDropActive = ref(false)

/**
 * 树里拖完的落点处理。
 *
 * <p>与「移动到…」共用同一个接口，只是把「输入目标目录」换成了「拖到哪个目录上」。
 * 落点合法性（自己、子目录、同名冲突）在 {@code DocTreeNode} 与后端各挡一道，
 * 这里只管发起与收尾。</p>
 */
async function onTreeMove(payload: { path: string; targetDir: string }) {
  const { path, targetDir } = payload
  if (!path) return
  const result = await docApi.move(kbSlug.value, { path, targetDir })
  if (result.path === path) return
  ElMessage.success('已移动')
  await loadTree()
  // 被拖的正是当前打开的那篇：路径变了，路由与锁都得跟着走，否则下次保存会写回老位置
  if (currentPath.value === path) {
    currentPath.value = result.path
    void syncRoutePath(result.path)
  } else if (currentPath.value.startsWith(`${path}/`)) {
    currentPath.value = result.path + currentPath.value.slice(path.length)
    void syncRoutePath(currentPath.value)
  }
}

/**
 * 同目录内排序：把被拖的项排到锚点行的前/后。
 *
 * <p>跨目录的拖拽走 {@code onTreeMove}（落点是目录行），这里只处理同目录的相对位置。</p>
 */
async function onTreeReorder(payload: { path: string; targetPath: string; position: 'before' | 'after' }) {
  const { path, targetPath, position } = payload
  if (!path || !targetPath) return
  await docApi.reorder(kbSlug.value, { path, targetPath, position })
  await loadTree()
}

function onRootDragOver(event: DragEvent) {
  // 只接树内部拖出来的节点：外部文件拖进左栏不该被当成「移到根目录」
  if (!kbCan('DOC_MOVE') || !draggingPath.value) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  rootDropActive.value = true
}

function onRootDragLeave(event: DragEvent) {
  // 容器里全是行，指针在行之间移动也会触发 dragleave，不排掉高亮就会闪
  const next = event.relatedTarget as Node | null
  if (next && (event.currentTarget as HTMLElement).contains(next)) return
  rootDropActive.value = false
}

function onRootDrop(event: DragEvent) {
  rootDropActive.value = false
  const path = draggingPath.value
  if (!kbCan('DOC_MOVE') || !path) return
  event.preventDefault()
  draggingPath.value = ''
  void onTreeMove({ path, targetDir: '' })
}

function onImportCommand(command: string) {
  if (command === 'dir') importDirInput.value?.click()
  else importInput.value?.click()
}

/**
 * 导入落点：跟在当前打开的文档走，它在哪个目录就导到哪个目录。
 * 选目录时文件自带相对路径（notes/sub/a.md），补上这个落点前缀即可还原整棵子树。
 */
const importBaseDir = () =>
  currentPath.value.includes('/')
    ? currentPath.value.slice(0, currentPath.value.lastIndexOf('/'))
    : ''

/**
 * 提取目录选择器里每个文件的知识库内相对路径。
 *
 * <p>webkitRelativePath 是「选择器根目录」的相对路径，而选择器根目录本身不导入，
 * 只导入它里面的内容。所以剥掉第一段（根目录名），剩下的拼进落点。
 * 文件直接拖进选择器（无目录时）兼容为空的情况。</p>
 */
function relativeOf(file: File) {
  const rel = (file as File & { webkitRelativePath?: string }).webkitRelativePath || ''
  const slash = rel.indexOf('/')
  return slash >= 0 ? rel.slice(slash + 1) : ''
}

/** 后端只认 Markdown 与 ZIP（见 DOC_EXTENSIONS），目录选择器却会把文件夹里的东西全交出来 */
const IMPORTABLE_FILE = /\.(md|markdown|zip)$/i

async function onImportChange(event: Event) {
  const input = event.target as HTMLInputElement
  const picked = Array.from(input.files || [])
  if (picked.length === 0) return
  /*
   * 先筛再传：选目录时浏览器会把图片、二进制、node_modules 之类一并交出来，后端拿到只会逐条跳过，
   * 白占带宽，还容易把请求顶过服务端的体积闸门换来一个 413。
   */
  const files = picked.filter((file) => IMPORTABLE_FILE.test(file.name))
  const ignored = picked.length - files.length
  if (files.length === 0) {
    input.value = ''
    ElMessage.warning('所选内容里没有可导入的 Markdown 或 ZIP 文件')
    return
  }
  const baseDir = importBaseDir()
  try {
    const result = await docApi.import(kbSlug.value, baseDir, files, relativeOf)
    ElMessage.success(`导入完成：成功 ${result.imported}，跳过 ${result.skipped}，失败 ${result.failed}`)
    if (result.messages?.length) {
      ElMessageBox.alert(result.messages.join('\n'), '导入明细', { confirmButtonText: '知道了' })
    }
    if (ignored > 0) ElMessage.info(`已忽略 ${ignored} 个非 Markdown / ZIP 文件`)
  } finally {
    input.value = ''
  }
  await loadTree()
  await loadKb()
}

/* ------------------------------------------------------------------ 顶部动作 */
async function copyText(text: string, tip: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(tip)
  } catch {
    ElMessageBox.alert(text, tip, { confirmButtonText: '知道了' })
  }
}

/* ------------------------------------------------------------------ 分享面板 */
const shareVisible = ref(false)
/** 面板当前的目标文档：工作区只分享单篇，整库分享在列表页那一行上（规范 §2.4 同一入口） */
const sharePath = ref('')
const shareTitle = computed(() => sharePath.value.split('/').pop() || '')

function openSharePanel(path?: string) {
  const target = path ?? currentPath.value
  if (!target) {
    ElMessage.warning('请先选择要分享的文档')
    return
  }
  sharePath.value = target
  shareVisible.value = true
}

function downloadCurrent() {
  if (!currentPath.value) return
  window.open(docApi.downloadUrl(kbSlug.value, currentPath.value), '_blank')
}

function openPortal() {
  window.open(appHref(portalKbUrl(org.value, kbSlug.value)), '_blank')
}

function onExportCommand(command: string) {
  if (command === 'markdown') downloadCurrent()
  else if (command === 'copy') void copyText(content.value, '正文已复制到剪贴板')
}

function backToTop() {
  previewHost()?.scrollTo({ top: 0, behavior: 'smooth' })
  const scroller = editorRef.value?.querySelector<HTMLElement>('.CodeMirror-scroll')
  scroller?.scrollTo({ top: 0, behavior: 'smooth' })
}

function backToList() {
  router.push({ name: 'kbs', params: { org: org.value } })
}

/* ------------------------------------------------------------------ 云端库同步 */
/** 只有云端库才显示拉取 / 推送：本地库没有远端，摆出来纯是噪音 */
const isCloudKb = computed(() => kb.value?.sourceType === 'git')
const gitStatus = ref<GitStatusVO | null>(null)
const gitBusy = ref(false)
/** 提交推送面板：动作本身在面板里，这里只持有开关与它要用的状态 */
const commitVisible = ref(false)

/* ---------------------------------------------------------------- 库设置面板 */
const settingsVisible = ref(false)

/* ---------------------------------------------------------------- 图片预览 */
/** 当前预览的库内图片；非 null 时编辑区让位给预览面板 */
const previewAsset = ref<DocNode | null>(null)

/**
 * 目录树点选：文档照旧打开，图片改成预览。
 *
 * <p>图片节点不能走 openDoc —— 它不是 markdown，{@code listDocPaths} 里根本没有它，
 * 真去读只会拿到 404。</p>
 */
function onTreeSelect(item: DocNode) {
  if (item.type === 'doc') {
    previewAsset.value = null
    void openDoc(item.path)
    return
  }
  if (item.type === 'image') {
    previewAsset.value = item
  }
}

/** 后台的资源端点与门户同形，只是前缀挂在 /api/console 下 */
const assetPrefix = computed(() => `/api/console/${encodeURIComponent(org.value)}/kbs/`
  + `${encodeURIComponent(kbSlug.value)}/asset/`)
/** 打开时顺带把文档树与库信息重取一次：隐藏规则一改，左栏与计数都会变 */
async function onSettingsSaved(payload: { kb?: KbVO }) {
  if (payload.kb) kb.value = payload.kb
  await loadTree()
  await loadKb()
}
function openSettings() {
  settingsVisible.value = true
}

/**
 * 后台克隆进行中。
 *
 * <p>建云端库时克隆已改成异步（后端提交事务后才拉代码），所以进到工作区可能什么都还没有 ——
 * 这时必须让用户看见「正在拉取」而不是「未就绪」，否则他会以为功能坏了，并去点「拉取」，
 * 而那时克隆还在跑，重复触发只会互相踩。</p>
 *
 * <p>判据是 {@code lastSyncOk == null}：后端只在「已排队 / 进行中」时把它留空，
 * 成功或失败都会写成 true / false。</p>
 */
const cloning = computed(() => !!kb.value?.git && kb.value.git.lastSyncOk === null
  && !!kb.value.git.lastSyncStatus)

/** 状态胶囊：把「有改动 / 落后 / 领先」合成一句话，一眼看出该点哪个按钮 */
const gitStateText = computed(() => {
  if (cloning.value) return '正在拉取仓库…'
  if (cloningFailed.value) return '拉取失败'
  const status = gitStatus.value
  // 「读取中」与「未就绪」必须分开：状态现在是异步补的，两者混用会让用户
  // 在结果到达前就以为这个库没有远端，然后去检查绑定、去看文档
  if (!status) return '读取中…'
  if (!status.repository) return '未就绪'
  const parts: string[] = []
  if (status.changedCount) parts.push(`${status.changedCount} 处改动`)
  if (status.behind) parts.push(`落后 ${status.behind}`)
  if (status.ahead) parts.push(`领先 ${status.ahead}`)
  return parts.length ? parts.join(' · ') : '已同步'
})

const cloningFailed = computed(() => kb.value?.git?.lastSyncOk === false)

const gitStateTitle = computed(() => {
  const git = kb.value?.git
  const status = gitStatus.value
  if (cloningFailed.value) {
    return git?.lastSyncStatus || '克隆失败，可在下方「拉取」重试'
  }
  return [
    git?.branch ? `分支 ${git.branch}` : '',
    git?.url || '',
    status?.headCommit ? `HEAD ${status.headCommit}` : '',
    git?.lastSyncAt ? `上次同步 ${git.lastSyncAt}` : '尚未同步过',
    git?.lastSyncStatus || ''
  ].filter(Boolean).join(' · ')
})

async function loadGitStatus() {
  if (!isCloudKb.value) {
    gitStatus.value = null
    return
  }
  try {
    gitStatus.value = await kbApi.gitStatus(kbSlug.value)
  } catch {
    /* 状态拿不到只是少一个提示，不影响读写文档 */
    gitStatus.value = null
  }
}

/**
 * 非阻塞地补一次工作副本状态。
 *
 * <p>git 状态是纯本地扫描（{@code git status} 把工作区跟索引逐个比对），
 * 实测 189 篇的库要 260ms、冷缓存能到 4s。它只驱动顶栏那颗胶囊
 * （「有 N 处改动 / 落后 M 个提交」），却曾被 await 在首屏 loading 里 ——
 * 于是目录树、编辑器、首篇文档全在等它，而它一慢整个工作区就打不开。
 *
 * <p>改成不 await：胶囊晚几百毫秒出现毫无影响，工作区先可用才是要紧的。
 * 顺带把请求挪到首屏渲染之后发出，避免与 loadTree / openDoc 抢带宽。</p>
 */
function refreshGitStatusSoon() {
  if (!isCloudKb.value) return
  void loadGitStatus()
}

/**
 * 轮询后台克隆进度，直到落终态。
 *
 * <p>只在「进行中」时启动：克隆是一次性的事件，不是需要盯着的实时状态，
 * 终态之后继续轮询就是纯浪费（而且会把接口日志刷满）。</p>
 *
 * <p>间隔 3s、上限 5 分钟：JGit 侧超时是 120s，留出余量足够覆盖慢仓库与重试；
 * 超时后停止并提示，不再无限轮询下去。</p>
 */
const CLONE_POLL_MS = 3000
const CLONE_POLL_LIMIT = 100
let clonePollTimer: number | undefined
let clonePollTicks = 0

function stopClonePolling() {
  if (clonePollTimer !== undefined) {
    window.clearTimeout(clonePollTimer)
    clonePollTimer = undefined
  }
  clonePollTicks = 0
}

function startClonePolling() {
  stopClonePolling()
  if (!cloning.value) return
  clonePollTimer = window.setTimeout(async () => {
    clonePollTimer = undefined
    clonePollTicks++
    await loadKb()
    // 这里刻意不再拉 git 状态：克隆进度由 kb.git.lastSyncOk 驱动（cloning 由它算出），
    // 而 status() 是本地全量扫描，每 3s 扫一遍 189 个文件纯属浪费。
    // 轮询到终态后由下面的分支补一次即可。
    if (cloning.value && clonePollTicks < CLONE_POLL_LIMIT) {
      startClonePolling()
    } else if (cloning.value) {
      ElMessage.warning('拉取耗时较长，已停止自动刷新，请手动刷新页面查看结果')
    } else if (cloningFailed.value) {
      ElMessage.error(kb.value?.git?.lastSyncStatus || '仓库拉取失败，可在下方「拉取」重试')
    } else {
      ElMessage.success('仓库拉取完成')
      // 终态这一次才补 git 状态：之前每轮都拉是纯浪费，此刻才是真有结果可读的时候
      refreshGitStatusSoon()
    }
  }, CLONE_POLL_MS)
}

// 状态从「进行中」翻到终态的那一刻起轮询；反向（重试后再次排队）同样要接上
watch(cloning, (now, before) => {
  if (now) {
    startClonePolling()
  } else if (before) {
    stopClonePolling()
  }
}, { immediate: true })

async function gitPull() {
  gitBusy.value = true
  try {
    const result = await kbApi.gitPull(kbSlug.value)
    ElMessage.success(result.message)
    // 拉进来的提交可能新增 / 改写了正文：目录与当前文档都得重取，否则看到的还是旧内容
    await loadKb()
    await loadTree()
    if (currentPath.value && !dirty.value) await openDoc(currentPath.value, true, true)
    else if (currentPath.value) ElMessage.warning('当前文档有未保存的修改，没有自动重载，请自行确认内容')
  } catch {
    /* 拦截器已提示（本地脏 / 冲突 / 认证失败） */
  } finally {
    await loadGitStatus()
    gitBusy.value = false
  }
}

/**
 * 「提交推送」只负责开面板，提交动作在 {@link GitCommitDialog} 里。
 *
 * <p>用户点它时最想知道的不是「成没成功」，而是「会提交哪些文件」——
 * 这个信息只有面板里有，所以这里不再直接打接口。</p>
 */
function openCommitDialog() {
  commitVisible.value = true
}

/** 面板提交成功后回到工作区：正文可能被这次推送带走了新提交，目录与计数都要重取 */
async function onCommitted() {
  await loadKb()
  await loadTree()
  await loadGitStatus()
}

/* ------------------------------------------------------------------ 面板宽度拖拽 */
/** 宽度下限：再窄就装不下「序号 + 一级标题」，也就不值得常驻了 */
const PANEL_MIN_W = 220
/** 上限跟着视口走：大纲是配角，不该把正文挤没 */
const panelMaxWidth = () => Math.min(560, Math.max(PANEL_MIN_W, window.innerWidth - 720))

/**
 * 拖动面板的一条竖边调宽。
 *
 * <p>三处面板共用这一套手法，差别只在把手贴哪条边、以及往哪边拖算变宽：
 * 左侧文档目录的把手在右沿（往右拖＝变宽），右栏大纲与 bytemd 自带大纲的把手在左沿
 * （往左拖＝变宽）。松手落盘。宽度只在拖过之后才由内联样式接管，没拖过时仍归样式表管，
 * 各档响应式断点照常生效。</p>
 *
 * @param edge 把手所在的边：{@code 'right'} 表示面板居左、往右拖变宽；{@code 'left'} 反之
 */
function createColumnResizer(
  panel: () => HTMLElement | null,
  options: { min: number; storageKey: string; edge?: 'left' | 'right' }
) {
  const width = ref(0)
  let startX = 0
  let startWidth = 0

  const clamp = (value: number) => Math.round(Math.min(panelMaxWidth(), Math.max(options.min, value)))

  function stop() {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    document.body.classList.remove('is-col-resizing')
  }

  function onMove(event: MouseEvent) {
    // 把手在右沿＝面板居左，指针往右拖变宽（起点加到当前）；把手在左沿则反过来减
    const delta = event.clientX - startX
    width.value = clamp(startWidth + (options.edge === 'right' ? delta : -delta))
  }

  function onUp() {
    stop()
    localStorage.setItem(options.storageKey, String(width.value))
  }

  function start(event: MouseEvent) {
    event.preventDefault()
    startX = event.clientX
    // 从实际渲染宽度起算，这样「样式表给的默认宽度」也能被无缝接管
    startWidth = panel()?.offsetWidth || width.value || options.min
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
    document.body.classList.add('is-col-resizing')
  }

  return {
    width,
    start,
    stop,
    /** 从本地偏好里恢复上次拖出来的宽度 */
    restore() {
      const saved = Number(localStorage.getItem(options.storageKey))
      if (saved > 0) width.value = clamp(saved)
    },
    /** 视口变了要重新夹一次：拖过之后内联宽度会盖掉媒体查询给的上限 */
    reflow() {
      if (width.value) width.value = clamp(width.value)
    }
  }
}

/** 工作区左栏：文档目录 */
const sidePanelRef = ref<HTMLElement | null>(null)
const sideResizer = createColumnResizer(() => sidePanelRef.value, {
  min: PANEL_MIN_W,
  storageKey: 'minidocs.ws.sideWidth',
  edge: 'right'
})
const sideWidth = sideResizer.width

const tocPanelRef = ref<HTMLElement | null>(null)
/** 工作区右栏大纲 */
const tocResizer = createColumnResizer(() => tocPanelRef.value, {
  min: PANEL_MIN_W,
  storageKey: 'minidocs.ws.tocWidth'
})
const tocWidth = tocResizer.width
/**
 * bytemd 自带的大纲侧栏（编辑态）。
 *
 * <p>宽度没法从外部塞给它，只能下一条 CSS 变量；把手也不是它的子节点，是盖在左沿上的一层。</p>
 */
const bytemdTocResizer = createColumnResizer(
  () => editorRef.value?.querySelector<HTMLElement>('.bytemd-sidebar') || null,
  { min: PANEL_MIN_W, storageKey: 'minidocs.ws.bytemdTocWidth' }
)
const bytemdTocWidth = bytemdTocResizer.width
/** 拖过才下发变量，没拖过仍用样式表里的默认宽度 */
const editorStyle = computed(() =>
  bytemdTocWidth.value ? { '--md-bytemd-toc-w': `${bytemdTocWidth.value}px` } : undefined
)

function onWindowResize() {
  sideResizer.reflow()
  tocResizer.reflow()
  bytemdTocResizer.reflow()
}

/* ------------------------------------------------------------------ 生命周期 */
function onKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    // 预览模式没有保存入口、也不持锁：拦下浏览器保存页即可，别再弹一句「正在获取编辑锁…」
    if (paneMode.value === 'edit') void save()
  }
}

function onBeforeUnload(event: BeforeUnloadEvent) {
  // 页面要没了就先还锁：不还得让人干等 TTL
  if (haveLock.value) void releaseLock(true)
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

/**
 * 只读态与编辑器之间的唯一连线。
 *
 * <p>@bytemd/vue-next 没有 readOnly 属性，只能在实例上改选项，因此「什么时候该改」必须有一个
 * 说得清的地方：跟着 {@code readOnly} 与 {@code currentPath} 走 —— 前者覆盖锁换手与权限变化，
 * 后者覆盖编辑器刚被创建出来的那一瞬（那时 {@code readOnly} 的值可能根本没变过）。</p>
 */
watch(
  [readOnly, currentPath],
  () => {
    void nextTick(() => setEditorReadOnly(readOnly.value))
  },
  { immediate: true }
)

onMounted(async () => {
  loading.value = true
  try {
    await loadKb()
    await loadTree()
    await nextTick()
    const requested = (route.query.path as string) || ''
    const first = flatDocs.value[0]
    const target = requested && flatDocs.value.some((doc) => doc.path === requested) ? requested : first?.path || ''
    if (target) await openDoc(target)
  } finally {
    loading.value = false
  }
  // 工作区已可用之后再补 git 状态：胶囊是次要信息，不该拖住首屏（该接口本地扫描要 260ms~4s）
  refreshGitStatusSoon()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('beforeunload', onBeforeUnload)
  window.addEventListener('resize', onWindowResize)
  sideResizer.restore()
  tocResizer.restore()
  bytemdTocResizer.restore()
})

watch(
  () => route.query.path,
  (value) => {
    const path = (value as string) || ''
    if (path && path !== currentPath.value) void openDoc(path)
  }
)

onBeforeUnmount(() => {
  alive = false
  stopClonePolling()
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('beforeunload', onBeforeUnload)
  window.removeEventListener('resize', onWindowResize)
  sideResizer.stop()
  tocResizer.stop()
  bytemdTocResizer.stop()
  window.clearTimeout(autoSaveTimer)
  window.clearTimeout(outlineTimer)
  window.clearTimeout(spyTimer)
  window.clearTimeout(readyTimer)
  unbindPreviewHost()
  // 路由切走也要还锁，否则同事得干等 TTL；组件已卸载，发出去就不管结果了
  void releaseLock(true)
})
</script>

<template>
  <div v-loading="loading" class="md-ws">
    <!-- 顶部操作区 -->
    <header class="md-ws__top">
      <button type="button" class="md-ws__back" @click="backToList">
        <MdIcon name="back" :size="15" />
        <span>返回</span>
      </button>

      <div class="md-ws__identity">
        <span class="md-ws__logo"><KbGlyph :size="19" /></span>
        <div class="md-ws__identity-text">
          <div class="md-ws__name">
            <span class="md-ws__name-text">{{ kb?.name || '知识库' }}</span>
            <span class="md-ad-chip" :class="visibilityChipClass(kb?.visibility)" :title="visibilityHint(kb?.visibility)">
              {{ visibilityText }}
            </span>
            <!-- 两条轴并排放：只看到「本组织」的人容易以为「能看到就能改」 -->
            <span class="md-ad-chip md-ad-chip--private" :title="maintainScopeHint(kb?.maintainScope)">
              可写：{{ maintainScopeLabel(kb?.maintainScope) }}
            </span>
            <!--
              发布态与上面两条轴正交，所以单列一颗而不是并进「可写」里：
              在工作区里改文档的人经常同时是那个要决定「要不要对外发布」的人，
              让他不用切回列表页就知道这个库目前门户上是什么状态。
            -->
            <span
              class="md-ad-chip"
              :class="publishStatusChipClass(kb?.shareStatus)"
              :title="publishStatusHint(kb?.shareStatus)"
            >{{ publishStatusLabel(kb?.shareStatus) }}</span>
            <span v-if="isCloudKb" class="md-ad-chip md-ad-chip--cloud" :title="kb?.git?.url || '绑定线上 Git 仓库'">
              云端
            </span>
          </div>
          <div class="md-ws__sub">
            {{ kb?.docCount ?? 0 }} 篇文档 · 更新于 {{ kb?.updatedText || formatDate(kb?.updatedAt) }}
          </div>
        </div>
      </div>

      <span class="md-spacer" />

      <div class="md-ws__actions">
        <!--
          保存只在编辑态出现：预览态没有可写入口，摆一个按不动的按钮只会让人误会。
          顺序上保存打头（写文档是最要紧的动作），导出收尾（低频，且是往外拿东西）。
        -->
        <button
          v-if="paneMode === 'edit'"
          type="button"
          class="md-ws__btn md-ws__btn--primary"
          :disabled="!currentPath || !haveLock || saving"
          :title="haveLock ? '保存当前文档（Ctrl / ⌘ + S）' : lockText"
          @click="() => save()"
        >
          <MdIcon :name="saving ? 'refresh' : 'check'" :size="14" :class="{ 'is-spin': saving }" />
          {{ saving ? '保存中' : '保存' }}
        </button>

        <span
          v-if="currentPath && saveStateText"
          class="md-ws__save-state"
          :class="{ 'is-dirty': dirty, 'is-busy': saving }"
        >
          <MdIcon
            :name="saving ? 'refresh' : dirty ? 'edit' : savedAuto ? 'clock' : 'check'"
            :size="13"
            :class="{ 'is-spin': saving }"
          />
          {{ saveStateText }}
        </span>

        <!--
          设置放在顶栏而不是左栏底部：它管的是「这个库是什么、哪些内容不给人看」，
          属于库级治理而不是文档级操作，和「分享」并排放在一起才说得通。
          v-if 用 kbCan 而不是常量 true——只读成员进来时按钮整个消失比灰着更好：
          那一栏可点的东西本来就都不能做。
        -->
        <button
          v-if="kbCan('KB_EDIT_META') || kbCan('KB_SET_VISIBILITY')"
          type="button"
          class="md-ws__btn"
          title="知识库设置：基本信息与隐藏配置"
          @click="openSettings"
        >
          <MdIcon name="settings" :size="14" />
          设置
        </button>

        <button
          v-if="kbCan('SHARE_CREATE')"
          type="button"
          class="md-ws__btn"
          :disabled="!currentPath"
          @click="() => openSharePanel()"
        >
          <MdIcon name="share" :size="14" />
          分享
        </button>

        <!--
          云端库的同步动作与「保存」是两件事：保存写的是磁盘工作副本，这里才是与远程仓库的收发。
          所以胶囊和按钮跟在保存状态之后，用独立配色，避免被读成同一个保存流程的两步。
        -->
        <template v-if="isCloudKb">
          <span
            class="md-ws__git-state"
            :class="{ 'is-dirty': gitStatus && !gitStatus.clean }"
            :title="gitStateTitle"
          >
            <MdIcon name="branch" :size="13" />
            {{ gitStateText }}
          </span>
          <button
            v-if="canWriteKb"
            type="button"
            class="md-ws__btn"
            :disabled="gitBusy || cloning"
            :title="cloning
              ? '仓库正在后台拉取，完成后才能拉取'
              : '把远程仓库的新提交拉到工作副本；本地有未提交改动时会拒绝'"
            @click="gitPull"
          >
            <MdIcon name="cloud" :size="14" />
            拉取
          </button>
          <button
            v-if="canWriteKb"
            type="button"
            class="md-ws__btn"
            :disabled="gitBusy || cloning"
            title="查看将要提交的文件并填写提交说明，然后提交并推送"
            @click="openCommitDialog"
          >
            <MdIcon name="arrow-up" :size="14" />
            提交推送
          </button>
        </template>

        <button type="button" class="md-ws__btn" @click="openPortal">
          <MdIcon name="external" :size="14" />
          预览
        </button>

        <el-dropdown trigger="click" @command="onExportCommand">
          <button type="button" class="md-ws__btn">
            <MdIcon name="download" :size="14" />
            导出
            <MdIcon name="chevron-down" :size="13" class="md-ws__btn-caret" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="markdown">
                <MdIcon name="download" :size="14" />下载 Markdown 原文
              </el-dropdown-item>
              <el-dropdown-item command="copy" divided>
                <MdIcon name="copy" :size="14" />复制正文 Markdown
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="md-ws__body">
      <!-- 窄屏抽屉的遮罩：点一下收起左右两栏，避免「浮层开着却不知道关在哪」 -->
      <div
        v-if="isMobile && (showTree || tocVisible)"
        class="md-ws__mask"
        @click="showTree = false; showToc = false"
      />
      <!-- 左：文档目录 + 知识库信息 -->
      <aside
        v-show="showTree"
        ref="sidePanelRef"
        class="md-ws__side"
        :style="sideWidth ? { width: `${sideWidth}px` } : undefined"
      >
        <!-- 拖拽把手压在面板右沿：这一栏在左，往右拖才是变宽 -->
        <div
          class="md-ws__side-resizer"
          title="拖动调整目录宽度"
          @mousedown="sideResizer.start"
        />
        <div class="md-ws__side-head">
          <span class="md-ws__side-title">文档目录</span>
          <span class="md-ws__count">{{ kb?.docCount ?? 0 }}</span>
          <span class="md-spacer" />
          <button type="button" class="md-ws__icon-btn" title="刷新目录" @click="loadTree">
            <MdIcon name="refresh" :size="15" />
          </button>
          <button
            type="button"
            class="md-ws__icon-btn"
            :title="collapseAll ? '展开全部' : '折叠全部'"
            @click="toggleCollapseAll"
          >
            <MdIcon :name="collapseAll ? 'chevron-right' : 'chevron-down'" :size="15" />
          </button>
        </div>

        <div class="md-ws__side-search">
          <el-input v-model="keyword" size="small" placeholder="搜索文档" clearable>
            <template #prefix>
              <MdIcon name="search" :size="14" />
            </template>
          </el-input>
        </div>

        <!--
          三个按钮挤在 226px 的侧栏里，标签必须短：写「新建文档」会折成「新建文 / 档」。
          加号图标已经表达了「新建」，完整说法交给 title。
        -->
        <div v-if="canWriteKb" class="md-ws__side-tools">
          <button type="button" class="md-ws__tool md-ws__tool--primary" title="新建文档" @click="() => createDoc('')">
            <MdIcon name="plus" :size="14" /> 文档
          </button>
          <button type="button" class="md-ws__tool" title="新建目录" @click="() => createDir('')">
            <MdIcon name="folder" :size="14" /> 目录
          </button>
          <!-- 选文件与选目录是两个不同的系统选择器，只能分两条入口 -->
          <el-dropdown trigger="click" placement="bottom-start" @command="onImportCommand">
            <button type="button" class="md-ws__tool" title="导入 Markdown、压缩包（zip）或整个目录">
              <MdIcon name="upload" :size="14" /> 导入
              <MdIcon name="chevron-down" :size="12" class="md-ws__tool-caret" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="files">
                  <MdIcon name="file" :size="14" />选择文件 / 压缩包…
                </el-dropdown-item>
                <el-dropdown-item command="dir">
                  <MdIcon name="folder" :size="14" />选择目录…
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <!-- 树容器的空白处就是「移到根目录」的落点 -->
        <div
          v-loading="treeLoading"
          class="md-ws__tree"
          :class="{ 'is-root-drop': rootDropActive }"
          @dragover="onRootDragOver"
          @dragleave="onRootDragLeave"
          @drop="onRootDrop"
        >
          <p v-if="visibleTree.length === 0" class="md-ws__tree-empty">
            {{ keyword ? '没有匹配的文档' : '还没有文档，先新建一篇吧' }}
          </p>
          <ul v-else class="md-ad-tree">
            <DocTreeNode
              v-for="(node, index) in visibleTree"
              :key="node.path"
              :node="node"
              :active-path="currentPath"
              :depth="0"
              :init-collapsed="!(index === 0 && node.type === 'dir')"
                     :collapse-all="collapseAll"
                  :perms="kb?.myPermissions"
                   :show-md-suffix="kb?.showMdSuffix"
              @select="onTreeSelect"
              @action="onTreeAction"
              @move="onTreeMove"
              @reorder="onTreeReorder"
            />
          </ul>
        </div>

        <div class="md-ws__kbinfo">
          <div class="md-ws__kbinfo-title">知识库信息</div>
          <div v-for="row in kbMetaRows" :key="row.label" class="md-ws__kbinfo-row">
            <span>{{ row.label }}</span>
            <span>{{ row.value }}</span>
          </div>
        </div>
      </aside>

      <!-- 中：编辑器 -->
      <main class="md-ws__main">
        <div class="md-ws__bar">
          <button
            type="button"
            class="md-ws__icon-btn"
            :title="showTree ? '收起文档目录' : '展开文档目录'"
            @click="showTree = !showTree"
          >
            <MdIcon name="panel-left" :size="15" />
          </button>

          <div class="md-ws__seg">
            <button
              v-for="item in paneOptions"
              :key="item.key"
              type="button"
              class="md-ws__seg-item"
              :class="{ 'is-active': paneMode === item.key }"
              @click="setPaneMode(item.key)"
            >
              <MdIcon :name="item.icon" :size="14" />
              <span>{{ item.label }}</span>
            </button>
          </div>

          <span class="md-ws__bar-sep" />

          <nav class="md-ws__crumbs">
            <template v-if="crumbs.length">
              <template v-for="(segment, index) in crumbs" :key="`${segment}-${index}`">
                <MdIcon v-if="index > 0" name="chevron-right" :size="12" class="md-ws__crumb-sep" />
                <span class="md-ws__crumb" :class="{ 'is-last': index === crumbs.length - 1 }">
                  {{ segment }}
                </span>
              </template>
            </template>
            <span v-else class="md-ws__crumb">未选择文档</span>
          </nav>

          <span class="md-spacer" />

          <span v-if="haveLock" class="md-ws__lock" :title="`锁 ${lock?.ttlSeconds ?? 0} 秒过期，编辑器会自动续期`">
            <MdIcon name="lock" :size="13" />{{ lockText }}
          </span>

          <button
            type="button"
            class="md-ws__icon-btn"
            :title="showToc ? '隐藏文章大纲' : '显示文章大纲'"
            :class="{ 'is-on': showToc }"
            @click="showToc = !showToc"
          >
            <MdIcon name="list" :size="15" />
          </button>
        </div>

        <div
          v-if="paneMode === 'edit' && readOnly && currentPath"
          class="md-ws__lockbar"
          :class="{ 'is-denied': !!lockDenied || writeDenied }"
        >
          <MdIcon name="lock" :size="14" />
          <span class="md-ws__lockbar-text">
            {{ lockText }}
            <template v-if="lockHint"> · {{ lockHint }}</template>
          </span>
          <button
            type="button"
            class="md-ws__lockbar-btn"
            :disabled="!!lockDenied || writeDenied"
            @click="() => acquireLock(currentPath)"
          >
            <MdIcon name="refresh" :size="13" />
            重新获取编辑锁
          </button>
        </div>

        <div ref="editorRef" class="md-ws__editor" :class="paneClass" :style="editorStyle">
          <!--
            选中库内图片时，编辑区让位给预览面板。
            放在 bytemd 之前而不是之后：预览态下不该还挂着一份编辑器实例（锁、光标心跳、
            未保存提示全都不该继续跑），而 v-if 天然把这两件事一起停掉。
          -->
          <ImagePreview
            v-if="previewAsset"
            :path="previewAsset.path"
            :name="previewAsset.name"
            :asset-prefix="assetPrefix"
            @close="previewAsset = null"
          />
          <div v-else-if="!currentPath && !loading" class="md-ws__blank">
            <div class="md-ws__blank-card">
              <span class="md-ws__blank-icon"><MdIcon name="file" :size="22" /></span>
              <h3>选择一篇文档开始编辑</h3>
              <p>从左侧目录选择文档，或新建一篇 Markdown 文档。</p>
              <el-button type="primary" @click="() => createDoc('')">
                <MdIcon name="plus" :size="14" />新建文档
              </el-button>
            </div>
          </div>

          <!-- @bytemd/vue-next 的根节点是无样式的 div，这里补一层 host 保证编辑器拿到确定高度 -->
          <div v-if="!!currentPath && !previewAsset" class="md-ws__editor-host">
            <Editor
              :value="content"
              :plugins="plugins"
              :locale="zhHans"
              :upload-images="uploadImages"
              :preview-debounce="200"
              mode="split"
              @change="onContentChange"
            />
          </div>

          <!-- bytemd 自带大纲的左沿把手：编辑态且侧栏真的展开时才由样式放出来 -->
          <div
            class="md-ws__bytemd-resizer"
            title="拖动调整目录宽度"
            @mousedown="bytemdTocResizer.start"
          />
        </div>

        <footer class="md-ws__status">
          <span class="md-ws__status-title">{{ displayTitle || '未选择文档' }}</span>
          <span class="md-spacer" />
          <span class="md-ws__metric">{{ words }} 字</span>
          <span class="md-ws__metric">{{ lines }} 行</span>
          <span class="md-ws__metric">{{ authorLabel }}</span>
          <span class="md-ws__metric">更新于 {{ formatDate(docInfo?.modifiedAt) }}</span>
          <button type="button" class="md-ws__icon-btn" title="回到顶部" @click="backToTop">
            <MdIcon name="arrow-up" :size="15" />
          </button>
        </footer>
      </main>

      <!-- 右：文章大纲 -->
      <aside
        v-if="tocVisible"
        ref="tocPanelRef"
        class="md-ws__toc"
        :style="tocWidth ? { width: `${tocWidth}px` } : undefined"
      >
        <!-- 拖拽把手压在面板左沿：面板越窄越需要一条明确的抓手 -->
        <div
          class="md-ws__toc-resizer"
          title="拖动调整目录宽度"
          @mousedown="tocResizer.start"
        />
        <div class="md-ws__toc-head">
          <span>目录</span>
          <span class="md-ws__count">{{ outline.length }}</span>
          <span class="md-spacer" />
          <button type="button" class="md-ws__icon-btn" title="关闭大纲" @click="showToc = false">
            <MdIcon name="close" :size="14" />
          </button>
        </div>
        <div class="md-ws__toc-body">
          <p v-if="outline.length === 0" class="md-ws__toc-empty">正文还没有标题</p>
          <ul v-else class="md-ad-outline__list">
            <li
              v-for="node in visibleOutline"
              :key="node.key"
              class="md-ad-outline__item"
              :data-level="node.level"
            >
              <div class="md-ad-outline__row" :class="{ 'is-active': activeOutlineKey === node.key }">
                <span class="md-ad-outline__link" :title="node.text" @click="scrollToHeading(node)">
                  {{ node.text }}
                </span>
                <!-- 有子标题才配箭头；点标题是跳转，点箭头才是展开 / 收起 -->
                <button
                  v-if="outlineParentKeys.has(node.key)"
                  type="button"
                  class="md-ad-outline__toggle"
                  :aria-expanded="isOutlineExpanded(node.key)"
                  :title="isOutlineExpanded(node.key) ? '收起' : '展开'"
                  @click="toggleOutline(node.key)"
                >
                  <MdIcon name="chevron-down" :size="13" />
                </button>
              </div>
            </li>
          </ul>
        </div>
      </aside>
    </div>

    <!-- 选文件：可多选，支持 .md / .zip，落到当前所在目录 -->
    <input
      ref="importInput"
      type="file"
      multiple
      accept=".md,.markdown,.zip"
      style="display: none"
      @change="onImportChange"
    />

    <!--
      选目录：webkitdirectory 是「整个文件夹」的取数口径，浏览器会把目录内的文件连同
      子目录一并给出，每个文件带 webkitRelativePath（形如 notes/sub/a.md）。
      它和 multiple 不能同时用，所以单独一个 input。
    -->
    <input
      ref="importDirInput"
      type="file"
      webkitdirectory
      multiple
      style="display: none"
      @change="onImportChange"
    />

    <ShareDialog v-model="shareVisible" :kb-slug="kbSlug" :doc-path="sharePath" :title="shareTitle" />

    <KbSettingsDialog
      v-model="settingsVisible"
      :kb="kb"
      :can-edit-meta="kbCan('KB_EDIT_META')"
      :can-edit-config="kbCan('KB_EDIT_META')"
      @saved="onSettingsSaved"
    />

    <GitCommitDialog
      v-if="isCloudKb"
      v-model="commitVisible"
      :kb-slug="kbSlug"
      :status="gitStatus"
      @committed="onCommitted"
    />
  </div>
</template>
