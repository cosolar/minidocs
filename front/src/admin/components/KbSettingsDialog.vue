<script setup lang="ts">
/**
 * 知识库设置面板：基础信息 + 隐藏配置。
 *
 * <p>隐藏配置那块刻意做成<b>已隐藏项照样列出、可以取消勾选</b>：树若是过滤后的，
 * 用户就只能加规则没法取消，配置就成了只写。接口因此单独给了一份未过滤的树。</p>
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import MdIcon from '@/admin/components/MdIcon.vue'
import { kbApi } from '@/admin/api'
import { visibilityHint } from '@/shared/visibility'
import type { DocNode, KbConfigVO, KbVO } from '@/shared/api/types'

const props = defineProps<{
  modelValue: boolean
  kb: KbVO | null
  canEditMeta: boolean
  canEditConfig: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved', payload: { kb?: KbVO }): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

/* ---------------------------------------------------------------- 基础信息 */

const form = ref({ name: '', description: '', tags: '', visibility: 'org' })
const savingMeta = ref(false)

function fillMeta(kb: KbVO) {
  form.value = {
    name: kb.name,
    description: kb.description || '',
    tags: (kb.tags || []).join('、'),
    visibility: kb.visibility
  }
}

/* ---------------------------------------------------------------- 隐藏配置 */

const config = ref<KbConfigVO | null>(null)
const loadingConfig = ref(false)
const savingConfig = ref(false)
/** 勾选中的路径。语义是「勾 = 隐藏」，所以面板文案必须反着说，否则用户会点反 */
const checked = ref<string[]>([])
/**
 * 目录树是否显示 {@code .md} 后缀。
 *
 * <p>单独一个 ref 而不是塞进 {@link #config}：它是「待保存的编辑态」，
 * 而 config 是服务端读回来的那份。两者混在一起就没法判断「改没改」（保存按钮的禁用
 * 依赖这个判断），也没法在取消时干净地丢掉未保存的改动。</p>
 */
const showMdSuffix = ref(true)

/** 开关的说明。默认值是「显示」，所以关掉它是一次明确的取舍，值得写清楚代价。 */
const showMdSuffixHint = computed(() => (showMdSuffix.value
  ? '目录树里显示「记忆碎片.md」这样的完整文件名。下载与重命名仍按磁盘上的真名走。'
  : '目录树里只显示「记忆碎片」，文件名补 .md。下载、搜索与重命名仍按磁盘上的真名走，不会丢扩展名。'))

/**
 * 「基础信息」页签里的后缀开关有没有待保存的改动。
 *
 * <p>两个开关在同一份配置上、却分属两个页签，所以「在另一个页签改了」这件事本身
 * 就是需要被看见的状态 —— 否则用户只能靠点一次保存、看完提示才知道自己漏了。</p>
 */
const displayDirty = computed(() => !!config.value
  && showMdSuffix.value !== (config.value.display?.showMdSuffix !== false))

const treeData = computed(() => (config.value?.tree || []).map(toTreeNode))

function toTreeNode(node: DocNode): Record<string, unknown> {
  return {
    id: node.path,
    label: node.name,
    nodeType: node.type,
    children: (node.children || []).map(toTreeNode)
  }
}

/**
 * 勾选变化。
 *
 * <p>el-tree 的 {@code check} 事件签名是 {@code (data, checkedInfo)}：第一个参数是
 * <b>被点的那个节点的数据对象</b>，第二个才是 {@code { checkedKeys, ... }}。
 * 早先这里取的是第一个参数，{@code checked} 于是变成节点对象；下一次渲染时
 * {@code checked.includes(...)} 抛 TypeError，整个自定义节点内容（图标 + 名称）渲染失败 ——
 * 表现就是「只剩复选框和折叠箭头，标签全没了」，而那两者是 el-tree 自己画的，所以还在。</p>
 *
 * <p>取 {@code checkedKeys} 而非 {@code checkedNodes}：前者是 {@code node-key}（这里就是路径），
 * 与 {@code .minidocs.json} 里 {@code hidden} 的元素类型一致，可以直接回写。</p>
 */
function onCheck(_data: unknown, info: { checkedKeys: (string | number)[] }) {
  checked.value = info.checkedKeys.map(String)
}

/** 目录 / 文档 / 图片三种节点给不同图标：图片是相框，混用 file 会让人以为是篇 Markdown。 */
function nodeIcon(nodeType: unknown): string {
  return nodeType === 'dir' ? 'folder' : nodeType === 'image' ? 'image' : 'file'
}

const hiddenCount = computed(() => checked.value.length)

/* ---------------------------------------------------------------- 取数与提交 */

async function loadConfig() {
  if (!props.kb) return
  loadingConfig.value = true
  try {
    config.value = await kbApi.config(props.kb.slug)
    checked.value = [...(config.value.hidden || [])]
    showMdSuffix.value = config.value.display?.showMdSuffix !== false
  } catch {
    ElMessage.error('读取库配置失败')
  } finally {
    loadingConfig.value = false
  }
}

watch(visible, (open) => {
  if (!open || !props.kb) return
  fillMeta(props.kb)
  void loadConfig()
})

async function saveMeta() {
  if (!props.kb) return
  if (!form.value.name.trim()) {
    ElMessage.warning('知识库名称不能为空')
    return
  }
  savingMeta.value = true
  try {
    // tags 传数组：后端 normalizeTags 按数组收，这里把顿号/逗号/空格都当分隔
    const tags = form.value.tags.split(/[、,，\s]+/).map((t) => t.trim()).filter(Boolean)
    const updated = await kbApi.update(props.kb.slug, {
      name: form.value.name.trim(),
      description: form.value.description.trim(),
      tags,
      visibility: form.value.visibility
    })
    ElMessage.success('基本信息已保存')
    emit('saved', { kb: updated })
  } finally {
    savingMeta.value = false
  }
}

async function saveHidden() {
  if (!props.kb || !config.value) return
  const next = [...checked.value]
  const sameHidden = JSON.stringify([...next].sort())
    === JSON.stringify([...(config.value.hidden || [])].sort())
  const sameDisplay = showMdSuffix.value === (config.value.display?.showMdSuffix !== false)
  if (sameHidden && sameDisplay) {
    ElMessage.info('配置没有变化')
    return
  }
  savingConfig.value = true
  try {
    await kbApi.saveConfig(props.kb.slug, next, { showMdSuffix: showMdSuffix.value })
    // 两个分区都提交，所以文案不能再只报隐藏那一条，否则改了后缀却说「已取消全部隐藏」
    const parts: string[] = []
    if (!sameHidden) parts.push(next.length ? `已隐藏 ${next.length} 项` : '已取消全部隐藏')
    if (!sameDisplay) parts.push(showMdSuffix.value ? '已恢复显示 .md 后缀' : '已隐藏 .md 后缀')
    ElMessage.success(parts.join('；'))
    // 规则一改，左栏与文档计数都会变；让工作区自己重取，面板不替它算
    emit('saved', {})
  } finally {
    savingConfig.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`知识库设置 · ${kb?.name || ''}`"
    width="680px"
    top="6vh"
  >
    <el-tabs>
      <!-- ------------------------------------------------------ 基础信息 -->
      <el-tab-pane label="基础信息">
        <el-form label-position="top" :disabled="!canEditMeta">
          <el-form-item label="名称">
            <el-input v-model="form.name" maxlength="64" show-word-limit />
          </el-form-item>
          <el-form-item label="简介">
            <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" show-word-limit />
          </el-form-item>
          <el-form-item label="标签">
            <el-input v-model="form.tags" placeholder="用顿号或逗号分隔，最多 5 个" />
            <div class="md-form-hint">最多 5 个，每个不超过 12 字</div>
          </el-form-item>
          <el-form-item label="可见范围（平台内谁能读）">
            <el-radio-group v-model="form.visibility">
              <el-radio-button value="private">仅维护名单</el-radio-button>
              <el-radio-button value="org">本组织成员</el-radio-button>
              <el-radio-button value="public">所有登录用户</el-radio-button>
            </el-radio-group>
            <div class="md-form-hint">{{ visibilityHint(form.visibility) }}</div>
          </el-form-item>
          <div class="md-form-hint">
            这一层只在<b>平台内</b>生效。库要出现在门户上、让不登录的人也能访问，需要另行创建整库分享链接。
          </div>

          <!--
            展示偏好。与上面的可见范围是两件不相干的事，所以单独一个 form-item：
            塞进同一块会让人以为「隐藏了目录」顺带也改了显示方式。
          -->
          <el-form-item label="目录树里的文件名">
            <div class="md-settings__switch">
              <el-switch
                v-model="showMdSuffix"
                :disabled="!canEditConfig"
                active-text="显示 .md"
                inactive-text="不显示"
              />
              <span class="md-form-hint">{{ showMdSuffixHint }}</span>
            </div>
          </el-form-item>
          <div class="md-form-hint">
            保存在库根的 <code>.minidocs.json</code>，跟着 Git 一起走 —— 团队共用一套显示方式。
          </div>
        </el-form>
        <div class="md-settings__actions">
               <el-button type="primary" :loading="savingMeta" :disabled="!canEditMeta" @click="saveMeta">
                    保存基本信息
                  </el-button>
         <!--
           基本信息与库级配置是两次不同的保存（前者写数据库、后者写库根文件），
           所以按钮也分开。但后缀开关是本页新加的，只给一个「保存基本信息」，
           用户会理所当然以为它一起生效了 —— 那就会去找「后缀怎么关不掉」。
         -->
            <el-button
              v-if="displayDirty"
              type="primary"
              plain
              :loading="savingConfig"
           :disabled="!canEditConfig"
              @click="saveHidden"
            >
                      保存后缀设置
               </el-button>
          </div>
      </el-tab-pane>

      <!-- ------------------------------------------------------ 隐藏配置 -->
      <el-tab-pane name="hidden">
        <template #label>
          隐藏配置
          <span v-if="hiddenCount" class="md-settings__badge">{{ hiddenCount }}</span>
        </template>

        <div v-loading="loadingConfig" class="md-settings__hidden">
          <!--
            勾 = 隐藏。el-tree 的勾选语义天然是「选中」，所以这里把标签写成
            「不显示」而不是「隐藏」，否则用户很容易按成「显示」。
          -->
          <p class="md-settings__tip">
            勾选的目录与文件将<b>不出现在目录树、搜索与文档计数里</b>，也不会随门户分享对外暴露。
            勾一个目录等于藏掉整棵子树，勾某个文件只藏它自己。
            规则保存在库根的 <code>.minidocs.json</code>，跟着 Git 一起走。
          </p>

          <!--
            check-strictly：勾选互不级联。这是「规则编辑器」不是「全选」，
            级联会把目录的每个后代都写成一条独立规则（189 篇的库就是 189 行 .minidocs.json），
            而且级联只对勾选那一刻已存在的文件生效 —— 之后往已隐藏目录里新增的文档反而会露出来。
            目录规则本身就覆盖整棵子树（后端 scan 遇到被隐藏的目录直接跳过），
            所以「勾目录 = 藏整棵」是免费的，不必靠级联凑。
          -->
          <el-tree
            v-if="config"
            :data="treeData"
            :props="{ label: 'label', children: 'children' }"
            node-key="id"
            show-checkbox
            check-strictly
            class="md-settings__tree"
            :default-checked-keys="checked"
            @check="onCheck"
          >
            <template #default="{ data }">
              <span class="md-settings__node">
                <MdIcon :name="nodeIcon(data.nodeType)" :size="13" />
                <span :class="{ 'is-hidden-now': checked.includes(data.id) }">{{ data.label }}</span>
                <em v-if="data.nodeType === 'dir'" class="md-settings__count">{{ data.children?.length || 0 }}</em>
              </span>
            </template>
          </el-tree>

          <el-empty v-else-if="!loadingConfig" description="库里还没有可配置的内容" />
        </div>

        <div class="md-settings__actions">
                  <el-button type="primary" :loading="savingConfig" :disabled="!canEditConfig" @click="saveHidden">
                    保存隐藏配置
                  </el-button>
                  <el-button :disabled="loadingConfig" @click="loadConfig">重新载入</el-button>
                  <!--
                    另一页签也有待保存的改动时不静默带过：saveHidden 会把两个分区一起提交，
                    所以这里必须说出来，否则用户在「基础信息」改了后缀却以为要点这个按钮。
                  -->
                  <span v-if="displayDirty" class="md-settings__crosshint">
                    「基础信息」里的文件名后缀设置也已改动，点这里会一并保存
                  </span>
                </div>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<style scoped>
/* 开关与说明同排：说明较长，竖排会把弹窗撑得很高 */
.md-settings__switch {
  display: flex;
  align-items: center;
  gap: 12px;
}
.md-settings__switch .md-form-hint { margin: 0; }
.md-settings__crosshint {
  align-self: center;
  color: var(--md-text-3);
  font-size: 12px;
}
.md-settings__tip {
  margin: 0 0 10px;
  color: var(--md-text-3);
  font-size: 12px;
  line-height: 1.6;
}
.md-settings__tree {
  max-height: 46vh;
  overflow: auto;
  border: 1px solid var(--md-border);
  border-radius: 8px;
  padding: 8px;
}
.md-settings__node {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
/* 已勾（即已隐藏）的项压暗并划线：这一栏叫「隐藏配置」，勾 = 不给人看 */
.md-settings__node .is-hidden-now {
  color: var(--md-text-3);
  text-decoration: line-through;
}
.md-settings__actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}
.md-settings__badge {
  margin-left: 4px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--md-primary-soft);
  color: var(--md-primary);
  font-size: 11px;
  line-height: 16px;
}
</style>
