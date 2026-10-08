<script setup lang="ts">
/**
 * 分享导航菜单配置器：从知识库目录树里勾选目录 / 文档，再按需要的顺序排。
 *
 * <p>勾选状态是<b>独立</b>的（el-tree 的 check-strictly）：勾一个目录不会顺带勾上它的子项，
 * 否则「只把某个目录做成菜单项」就永远做不到 —— 那正是最常用的一种。</p>
 *
 * <p>顺序以 modelValue 为准，而不是 el-tree 的勾选顺序：树只能给出「勾了哪些」，给不出
 * 「先看哪一篇」，所以新增项一律追加到末尾，再靠上下移调整。</p>
 */
import { nextTick, ref, watch } from 'vue'
import { docApi } from '@/admin/api'
import MdIcon from '@/admin/components/MdIcon.vue'
import type { DocNode, NavMenuItem } from '@/shared/api/types'
import { NAV_ICONS, isNavIcon, navIconOf } from '@/shared/navIcons'

const props = defineProps<{ kbSlug: string; modelValue: NavMenuItem[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: NavMenuItem[]] }>()

interface TreeOption {
  path: string
  name: string
  type: 'dir' | 'doc'
  children: TreeOption[]
}

const loading = ref(false)
const tree = ref<TreeOption[]>([])
const index = ref(new Map<string, TreeOption>())
const treeRef = ref()
/** 程序化同步勾选态时压住 check 回调：它不是用户操作，不该参与排序 */
let syncing = false

function toOptions(nodes: DocNode[]): TreeOption[] {
  return nodes.map((node) => ({
    path: node.path,
    name: node.name,
    type: node.type,
    children: toOptions(node.children || [])
  }))
}

function collect(nodes: TreeOption[], map: Map<string, TreeOption>) {
  for (const node of nodes) {
    map.set(node.path, node)
    collect(node.children, map)
  }
}

watch(
  () => props.kbSlug,
  async (slug) => {
    if (!slug) return
    loading.value = true
    try {
      const options = toOptions(await docApi.tree(slug))
      const map = new Map<string, TreeOption>()
      collect(options, map)
      tree.value = options
      index.value = map
      await nextTick()
      syncChecked()
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)

/** 外部改了清单（例如打开弹窗时读回已存的配置）也要把勾选态拉回来 */
watch(() => props.modelValue, () => void nextTick(syncChecked), { deep: true })

function syncChecked() {
  syncing = true
  treeRef.value?.setCheckedKeys(props.modelValue.map((item) => item.path), false)
  syncing = false
}

function onCheck() {
  if (syncing) return
  const keys = (treeRef.value?.getCheckedKeys() as string[]) || []
  const picked = new Set(keys)
  // 先按原顺序保留仍在勾选中的，再把新勾上的按树序追加到末尾
  const next = props.modelValue.filter((item) => picked.has(item.path))
  const known = new Set(next.map((item) => item.path))
  for (const key of keys) {
    if (known.has(key)) continue
    const node = index.value.get(key)
    if (node) next.push({ type: node.type, path: node.path, name: node.name })
  }
  emit('update:modelValue', next)
}

function move(at: number, delta: number) {
  const next = [...props.modelValue]
  const to = at + delta
  if (to < 0 || to >= next.length) return
  ;[next[at], next[to]] = [next[to], next[at]]
  emit('update:modelValue', next)
}

function removeAt(at: number) {
  emit('update:modelValue', props.modelValue.filter((_, i) => i !== at))
}

/**
 * 改某一項的别名。
 *
 * <p>就地改而不是整体替换：40 项的清单里每敲一个键都重建数组，输入框会失焦。
 * 存的是空串而不是 undefined，让「清空别名」与「从没取过别名」在界面上是同一个状态。</p>
 */
function setAlias(at: number, value: string) {
  const next = [...props.modelValue]
  next[at] = { ...next[at], alias: value.trim() || undefined }
  emit('update:modelValue', next)
}

/**
 * 改某一项的图标。undefined = 交给类型默认（目录书架 / 文档单篇）。
 *
 * <p>同样不就地改对象而是换新数组：清单里有别名输入框，重渲染会让输入焦点跑掉。</p>
 */
function setIcon(at: number, key: string | undefined) {
  const next = [...props.modelValue]
  next[at] = { ...next[at], icon: isNavIcon(key) ? key : undefined }
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="md-menu-pick">
    <!-- 已选清单：顺序就是分享页导航条上的顺序 -->
    <ol v-if="modelValue.length" class="md-menu-pick__list">
      <li v-for="(item, i) in modelValue" :key="item.path" class="md-menu-pick__row">
        <span class="md-menu-pick__idx">{{ i + 1 }}</span>
        <MdIcon class="md-menu-pick__icon" :name="item.type === 'dir' ? 'folder' : 'file'" :size="13" />
        <span class="md-menu-pick__label" :title="item.path">{{ item.name || item.path }}</span>
        <!--
          别名输入框。目录名是给作者看的（docs / notes），读者看到的是导航条上的文字，
          两者没有必然关系 —— 作者把 docs 呈现给读者叫「文档」是常事，所以这一项要能改。
        -->
        <input
          class="md-menu-pick__alias"
          :value="item.alias || ''"
          :placeholder="item.name || '显示名'"
          maxlength="32"
          :title="`分享页导航条上显示的名字（最多 32 字）；留空则显示「${item.name || item.path}」`"
          @input="setAlias(i, ($event.target as HTMLInputElement).value)"
        >
        <!--
          图标选择。目录名常是「文档」「笔记」这类通用词，没有图标时读者
          分不出这几个入口分别通向哪里 —— 顶栏导航条那排小图标就是入口辨识的唯一线索。
          留空表示「按类型用默认」（目录书架 / 文档单篇）。
        -->
        <el-popover placement="bottom-start" :width="268" trigger="click">
          <template #reference>
            <button type="button" class="md-menu-pick__icon-btn" title="选择图标">
              <MdIcon :name="navIconOf(item.icon, item.type)" :size="14" />
            </button>
          </template>
          <div class="md-icon-grid">
            <button
              type="button"
              class="md-icon-grid__cell"
              :class="{ 'is-on': !item.icon }"
              title="按类型用默认"
              @click="setIcon(i, undefined)"
            >
              <MdIcon name="refresh" :size="13" />
              <span>默认</span>
            </button>
            <button
              v-for="opt in NAV_ICONS"
              :key="opt.key"
              type="button"
              class="md-icon-grid__cell"
              :class="{ 'is-on': item.icon === opt.key }"
              :title="opt.label"
              @click="setIcon(i, opt.key)"
            >
              <MdIcon :name="opt.key" :size="13" />
              <span>{{ opt.label }}</span>
            </button>
          </div>
        </el-popover>
        <span class="md-menu-pick__ops">
          <button type="button" title="上移" :disabled="i === 0" @click="move(i, -1)">
            <MdIcon name="arrow-up" :size="13" />
          </button>
          <button
            type="button"
            title="下移"
            :disabled="i === modelValue.length - 1"
            @click="move(i, 1)"
          >
            <MdIcon class="is-flip" name="arrow-up" :size="13" />
          </button>
          <button type="button" title="移除" @click="removeAt(i)">
            <MdIcon name="close" :size="13" />
          </button>
        </span>
      </li>
    </ol>
    <p v-else class="md-menu-pick__empty">还没有菜单项。不选的话，分享页照旧显示完整目录树。</p>

    <el-tree
      ref="treeRef"
      v-loading="loading"
      class="md-menu-pick__tree"
      :data="tree"
      node-key="path"
      show-checkbox
      check-strictly
      check-on-click-node
      default-expand-all
      :expand-on-click-node="false"
      :props="{ label: 'name', children: 'children' }"
      @check="onCheck"
    >
      <template #default="{ data }">
        <span class="md-menu-pick__node">
          <MdIcon class="md-menu-pick__icon" :name="data.type === 'dir' ? 'folder' : 'file'" :size="13" />
          <span class="md-menu-pick__label">{{ data.name }}</span>
        </span>
      </template>
    </el-tree>
  </div>
</template>
