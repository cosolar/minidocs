<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import MdIcon from '@/admin/components/MdIcon.vue'
import { draggingPath } from '@/admin/components/docDrag'
import { can } from '@/shared/api/caps'
import { displayName } from '@/shared/docName'
import type { DocNode, KbActionName } from '@/shared/api/types'

const props = defineProps<{
  node: DocNode
  activePath: string
  depth: number
  /** 由工作区统一广播的折叠信号：切换时把本级目录一并展开/收起 */
  collapseAll?: boolean
  /**
   * 初始是否折叠。缺省为「折叠」。
   *
   * <p>默认折叠是刻意的：一个库动辄上百篇，全部铺开会把左栏拉成一条看不到头的长尾，
   * 而刚进工作区的人真正关心的是「现在开着哪一篇」和它沿途那一层。父级（工作区）只给
   * 顶层第一个目录传 {@code false}，让它作为「入口目录」保持展开。</p>
   *
   * <p>用「初始」而不是「当前」：用户手动开合过的目录不该被父级重置，
   * 所以这个值只在实例创建时读一次（见 {@code collapsed} 的初值）。</p>
   */
  initCollapsed?: boolean
  /**
   * 我在这个库上的动作向量（后端下发，规范 §2.6）。
   *
   * <p>逐项判定而不是一个「可写」布尔：删文档、改名、分享各有各的动作，合成一个布尔就等于
   * 让前端自己猜它们同不同轴。省略或给空数组按「本次没算」处理，菜单照常给，真判定在后端。</p>
   */
  perms?: KbActionName[]
  /**
   * 目录树是否显示 {@code .md} 后缀（库级配置）。缺省按显示处理。
   *
   * <p>逐层往下传而不是各自去读配置：递归组件的每一节都只持有自己直接渲染的那一节，
   * 在叶子节点里发请求既做不到也毫无意义。</p>
   */
  showMdSuffix?: boolean
}>()

const emit = defineEmits<{
  (e: 'select', node: DocNode): void
  (e: 'action', payload: { action: string; node: DocNode }): void
  (e: 'move', payload: { path: string; targetDir: string }): void
  (e: 'reorder', payload: { path: string; targetPath: string; position: 'before' | 'after' }): void
}>()

/**
 * 缺省收起；父级显式给 {@code initCollapsed: false} 的那一个（顶层入口目录）初始展开。
 *
 * <p>读 props 的时点只在初始化，之后 {@code collapseAll} 与「祖先自动展开」两条 watch
 * 才会改它 —— 初始值只决定「第一次进来长什么样」。</p>
 */
const collapsed = ref(props.initCollapsed !== false)

/** 拖动中的这一行本身：给它降透明度，让人看得出「拿起的是哪一个」 */
const isDragging = computed(() => draggingPath.value === props.node.path)

/**
 * 落点类型。
 *
 * <p>分两段判定：行的上/下缘＝排序（插到本行前/后），目录行的中段＝移入本目录。
 * 这样一条拖拽既能在同目录里挪位置，也能把节点放进某个目录，而不用两个手势。</p>
 */
const dropZone = ref<'' | 'before' | 'after' | 'inside'>('')
const dropActive = computed(() => dropZone.value === 'inside')

const parentPath = computed(() => {
  const index = props.node.path.lastIndexOf('/')
  return index < 0 ? '' : props.node.path.slice(0, index)
})

const draggingParent = computed(() => {
  const index = draggingPath.value.lastIndexOf('/')
  return index < 0 ? '' : draggingPath.value.slice(0, index)
})

const allowed = (action: KbActionName) => can(props.perms, action)
/**
 * 这一行的「⋯」要不要出现。
 *
 * <p>文档总有一条「下载 Markdown」可给，所以文档恒有菜单；目录只剩治理类动作，一个都没有时
 * 点开一个全灰的菜单比没有菜单更让人找不着北。</p>
 */
const isActive = computed(() => props.node.type === 'doc' && props.activePath === props.node.path)
/** 当前文档位于本目录之内：高亮 + 自动展开，避免定位不到正在编辑的文件 */
const isAncestor = computed(
  () => props.node.type === 'dir' && !!props.activePath && props.activePath.startsWith(`${props.node.path}/`)
)

/**
 * 节点类型图标。文档用 GitHub 标记 —— Markdown 在 UI 上没有专属抽象图形，
 * 行业惯例就是这个（代码托管平台与渲染器都用它），用户不用学就知道点开是什么。
 * 它是填充型图标，与 folder / image 的线条风格不同，但靠 currentColor 取色，
 * 在灰蓝色的文档行上不会显得突兀。
 */
const typeIcon = computed(() => (props.node.type === 'dir' ? 'folder' : props.node.type === 'image' ? 'image' : 'markdown'))

/**
 * 这一行显示的名字。
 *
 * <p>{@code name} 是磁盘上的真名（含 {@code .md}），重命名对话框要用它回填，所以树上显示的
 * 不能是它 —— 走 {@link displayName} 按库级偏好剥一次。这样「重命名」看到的输入框
 * 与树上看到的名字可能不一致，但各自都对：前者是文件名，后者是展示名。</p>
 */
const displayText = computed(() => displayName(props.node.name, props.node.type, props.showMdSuffix))

/**
 * 目录右侧的篇数：整棵子树里的文档数，不是直接子项数。
 *
 * <p>工作区里这个数字尤其有用 —— 目录多起来之后，「哪个目录是主库」只能靠这个数判断，
 * 直接子项数在「docs 下面 7 个子目录」时会给出 7，与实际 17 篇对不上。</p>
 *
 * <p>递归统计的是纯数据（不带权限过滤），所以只算「体量」不算「你能读到多少」，
 * 与树上真正渲染出来的行数可能不一致 —— 宁可给一个稳定的上界，也不要让数字随权限跳。</p>
 */
const docCount = computed(() => {
  if (props.node.type !== 'dir') return 0
  let total = 0
  for (const child of props.node.children || []) {
    total += child.type === 'doc' ? 1 : countDocs(child)
  }
  return total
})

function countDocs(node: DocNode): number {
  let total = 0
  for (const child of node.children || []) {
    total += child.type === 'doc' ? 1 : countDocs(child)
  }
  return total
}

/**
 * 图片不该有「⋯」菜单。
 *
 * <p>菜单里那些动作（重命名 / 移动 / 下载 markdown / 删除）要么对图片没有意义，
 * 要么会让人以为图片能像文档一样被编辑。给它一个「下载」就够了 ——
 * 而下载走资源端点即可，不必在树上再开一条路径。</p>
 */
const showMenu = computed(() => {
  if (props.node.type === 'image') return false
  return props.node.type === 'doc'
    || allowed('DOC_WRITE')
    || allowed('DOC_RENAME')
    || allowed('DOC_MOVE')
    || allowed('DOC_DELETE')
})

watch(
  () => props.collapseAll,
  (value) => {
    collapsed.value = Boolean(value)
  }
)

watch(
  () => props.activePath,
  () => {
    if (isAncestor.value) collapsed.value = false
  },
  { immediate: true }
)

function toggle() {
  collapsed.value = !collapsed.value
}

/**
 * 点行名。
 *
 * <p><b>目录是展开/收起，文档与图片才是打开。</b>此前一律 emit select，而工作区那边
 * 只处理 doc 与image，目录落空什么都不做 —— 于是「点目录名展开」这个所有文件树的
 * 基本行为在这里不成立，用户只能去点那个16px 的小箭头，看起来就是「点击没反应」。
 * 目录名与箭头是两个入口，但语义必须一致：箭头能展开，名字也该能。</p>
 */
function onSelect() {
  if (props.node.type === 'dir') {
    toggle()
    return
  }
  emit('select', props.node)
}

function onAction(action: string) {
  emit('action', { action, node: props.node })
}

/* ------------------------------------------------------------------ 拖拽移动 */
/** 没有移动权限的人连拿都拿不起来：能拖却拖不动比不能拖更让人困惑 */
const canDrag = computed(() => allowed('DOC_MOVE'))

/**
 * 这个目录能不能接住当前拖动的节点。
 *
 * <p>拖到自己、或拖到自己内部的子目录都是无效落点——后者会让目录凭空消失。
 * 后端也会拦（§「不能将目录移动到其自身或子目录内」），但先在这里挡掉能省一次白跑的请求。</p>
 */
const canDrop = computed(() => {
  const from = draggingPath.value
  if (!from || props.node.type !== 'dir' || !allowed('DOC_MOVE')) return false
  return props.node.path !== from && !props.node.path.startsWith(`${from}/`)
})

/**
 * 能不能把拖动项排到本行前/后。
 *
 * <p>只允许同目录内排序：跨目录的意图是「移动」，落点应该是目录行而不是某一行。</p>
 */
const canReorder = computed(() => {
  const from = draggingPath.value
  if (!from || from === props.node.path || !allowed('DOC_MOVE')) return false
  return draggingParent.value === parentPath.value
})

/**
 * 根据指针在行内的纵向位置判定落点。
 *
 * <p>目录行中段（30%~70%）留给「移入」；其余位置——文件行的整行、目录行的上下缘——都是排序。
 * 目录行的上下缘仍保留「移入」兜底，免得跨目录拖来时只剩一条缝能放。</p>
 */
function zoneOf(event: DragEvent): '' | 'before' | 'after' | 'inside' {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const ratio = rect.height === 0 ? 0.5 : (event.clientY - rect.top) / rect.height
  if (props.node.type === 'dir' && ratio > 0.3 && ratio < 0.7) {
    return canDrop.value ? 'inside' : ''
  }
  if (canReorder.value) {
    return ratio < 0.5 ? 'before' : 'after'
  }
  return canDrop.value ? 'inside' : ''
}

function onDragStart(event: DragEvent) {
  if (!canDrag.value) return
  draggingPath.value = props.node.path
  dropZone.value = ''
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    // Firefox 不设 data 就不启动拖拽，内容本身用不上，占个位即可
    event.dataTransfer.setData('text/plain', props.node.path)
  }
}

function onDragEnd() {
  draggingPath.value = ''
  dropZone.value = ''
}

function onDragOver(event: DragEvent) {
  if (!draggingPath.value) return
  /*
   * 树容器（空白处）也挂着「移到根目录」的落点。只要指针还在某一行上，就不该让容器跟着亮，
   * 否则拖到文件行（它自己不是合法落点）会被容器接管、把文档莫名其妙地移到根目录去。
   */
  event.stopPropagation()
  const zone = zoneOf(event)
  if (!zone) return
  // 只有 preventDefault 才表示「这里可以放」，否则浏览器给的是禁止光标
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  dropZone.value = zone
}

function onDragLeave(event: DragEvent) {
  // 行内有图标和文字，指针掠过子元素也会触发 dragleave，不排掉就会一直闪
  const next = event.relatedTarget as Node | null
  if (next && (event.currentTarget as HTMLElement).contains(next)) return
  dropZone.value = ''
}

function onDrop(event: DragEvent) {
  const zone = dropZone.value
  if (!zone) return
  event.preventDefault()
  // 别让事件继续冒泡到树容器（那是「移到根目录」的落点），否则一次拖拽会落两次
  event.stopPropagation()
  const from = draggingPath.value
  dropZone.value = ''
  draggingPath.value = ''
  if (zone === 'inside') {
    emit('move', { path: from, targetDir: props.node.path })
  } else {
    emit('reorder', { path: from, targetPath: props.node.path, position: zone })
  }
}
</script>

<template>
  <li class="md-ad-tree__item" :class="{ 'is-collapsed': collapsed && node.type === 'dir' }">
    <div
      class="md-ad-tree__row"
      :class="{
        'is-active': isActive,
        'is-ancestor': isAncestor,
        'is-dir': node.type === 'dir',
        'is-dragging': isDragging,
        'is-drop-target': dropActive,
        'is-drop-before': dropZone === 'before',
        'is-drop-after': dropZone === 'after'
      }"
      :draggable="canDrag"
      @dragstart="onDragStart"
      @dragend="onDragEnd"
      @dragover="onDragOver"
      @dragleave="onDragLeave"
      @drop="onDrop"
    >
      <span v-if="dropZone === 'before'" class="md-ad-tree__drop-line is-before" aria-hidden="true" />
      <span v-if="dropZone === 'after'" class="md-ad-tree__drop-line is-after" aria-hidden="true" />
      <button
        v-if="node.type === 'dir'"
        type="button"
        class="md-ad-tree__toggle"
        :aria-expanded="!collapsed"
        @click.stop="toggle"
      >
        <MdIcon :name="collapsed ? 'chevron-right' : 'chevron-down'" :size="12" />
      </button>
      <span v-else class="md-ad-tree__toggle md-ad-tree__toggle--spacer" />

      <MdIcon class="md-ad-tree__type" :name="typeIcon" :size="15" />

      <!-- title 分两种：目录说「点开合」，文档才给完整路径（悬停看全名是这里的刚需） -->
      <span
        class="md-ad-tree__name"
        :class="{ 'is-asset': node.type === 'image' }"
        :title="node.type === 'dir' ? '点击展开 / 收起' : node.path"
        @click="onSelect"
      >{{ displayText }}</span>

      <!--
        篇数排在「更多」左边而不是行的最右：最右那一列平时是透明的（opacity:0），
        计数若占了它的位置，鼠标一移开数字就跟着消失，看着像闪了一下。
      -->
      <em v-if="node.type === 'dir' && docCount" class="md-ad-tree__count">{{ docCount }}</em>

      <el-dropdown v-if="showMenu" trigger="click" placement="bottom-end" @command="onAction">
        <button type="button" class="md-ad-tree__more" title="更多操作" @click.stop>
          <MdIcon name="more" :size="15" />
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <template v-if="node.type === 'dir'">
              <el-dropdown-item v-if="allowed('DOC_WRITE')" command="new-doc">
                <MdIcon name="plus" :size="14" />在此新建文档
              </el-dropdown-item>
              <el-dropdown-item v-if="allowed('DOC_WRITE')" command="new-dir">
                <MdIcon name="folder" :size="14" />新建子目录
              </el-dropdown-item>
            </template>
            <el-dropdown-item v-else command="download">
              <MdIcon name="download" :size="14" />下载 Markdown
            </el-dropdown-item>
            <!-- 分享权等同写权（规范 §2.4），但它是一个单独的动作：只读成员能复制链接，不能建对外链接 -->
            <el-dropdown-item v-if="node.type === 'doc' && allowed('SHARE_CREATE')" command="share">
              <MdIcon name="share" :size="14" />分享本文档
            </el-dropdown-item>
            <el-dropdown-item v-if="allowed('DOC_RENAME')" command="rename" divided>
              <MdIcon name="edit" :size="14" />重命名
            </el-dropdown-item>
            <el-dropdown-item v-if="allowed('DOC_MOVE')" command="move">
              <MdIcon name="move" :size="14" />移动到…
            </el-dropdown-item>
            <el-dropdown-item v-if="allowed('DOC_DELETE')" command="delete" divided>
              <MdIcon name="trash" :size="14" />删除
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <ul v-if="node.type === 'dir' && node.children && node.children.length" class="md-ad-tree">
      <DocTreeNode
        v-for="child in node.children"
        :key="child.path"
        :node="child"
        :active-path="activePath"
        :depth="depth + 1"
        :collapse-all="collapseAll"
        :perms="perms"
        :show-md-suffix="showMdSuffix"
        @select="emit('select', $event)"
        @action="emit('action', $event)"
        @move="emit('move', $event)"
        @reorder="emit('reorder', $event)"
      />
    </ul>
  </li>
</template>
