<script setup lang="ts">
/**
 * 提交推送面板：先看清「有哪些文件变了」，再自己写一句提交说明，然后提交并推送。
 *
 * <p>此前这个动作是一个按钮直接打后端的，用户既不知道会提交什么，也没法留下自己的说明 ——
 * 而提交说明是 git 里唯一能长期留下来的上下文，事后没人能从「更新知识库内容」里回忆
 * 当时改了什么。</p>
 *
 * <p>面板不提供「只提交某几个文件」：后端是一次 {@code add .} + {@code setUpdate} 全量提交，
 * 勾选语义在接口层面就不存在。这里要做的是<b>让提交范围可预期</b>，不是引入选择性提交。</p>
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import MdIcon from '@/admin/components/MdIcon.vue'
import { kbApi } from '@/admin/api'
import type { GitStatusVO } from '@/shared/api/types'

const props = defineProps<{
  modelValue: boolean
  kbSlug: string
  /** 外层已持有的状态，用于面板打开瞬间就能显示，不必等这一次请求 */
  status: GitStatusVO | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 提交成功：外层要重取库信息与目录（正文可能被这次推送带走了新提交） */
  committed: []
}>()

/** 与后端 {@code GitSyncRequest} 的 @Size(max = 200) 对齐，超了后端会直接驳回 */
const MAX_MESSAGE = 200

const loading = ref(false)
const saving = ref(false)
const message = ref('')
const local = ref<GitStatusVO | null>(null)

/** 面板打开时重取一次：外层那份状态可能已是几十秒前的（打开文档不刷新它） */
const current = computed(() => local.value || props.status)

const changedPaths = computed(() => current.value?.changedPaths || [])
const hasChanges = computed(() => changedPaths.value.length > 0)

/** 提交前先把状态拉齐：用户可能改完文档直接点进来，外层那份已经过期 */
watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    message.value = ''
    local.value = null
    loading.value = true
    try {
      local.value = await kbApi.gitStatus(props.kbSlug)
    } catch {
      /* 读不到就退回用外层那份：面板照常能提交，只是文件列表可能不全 */
    } finally {
      loading.value = false
    }
  }
)

/**
 * 按钮文案跟着实际情况变。
 *
 * <p>没有待提交文件时，后端不会创建空提交，而是直接推一次 —— 这个场景是真实存在的
 * （本地已提交、但上次推送失败），所以不能把按钮禁掉，只换一个更准确的说法。</p>
 */
const submitText = computed(() => (hasChanges.value ? '提交并推送' : '确认推送'))

const submitHint = computed(() => {
  if (!hasChanges.value) {
    return '工作区没有未提交的改动，这一步只会把本地已有的提交推送到远程。'
  }
  return `将提交 ${changedPaths.value.length} 个文件并推送到远程分支。文件清单会自动附在提交说明之后。`
})

async function submit() {
  saving.value = true
  try {
    // 留空时传 undefined 而不是空串：后端把空白当作「用默认说明」，
    // 空串会被 commitMessage 里的 isBlank 同样处理，但显式 undefined 更明确
    const result = await kbApi.gitCommit(props.kbSlug, message.value.trim() || undefined)
    ElMessage.success(result.message)
    emit('committed')
    emit('update:modelValue', false)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="提交并推送"
    width="600px"
    align-center
    class="md-kb-dialog md-git-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="md-git">
      <!--
        推送前把「会推上去什么」摆在明面上。领先/落后一并给出：本次推送会把本地
        领先的提交一起送上去，而落后的部分这次拉不下来 —— 用户点之前就该知道这两件事。
      -->
      <section class="md-git-summary">
        <span class="md-git-chip" :class="{ 'is-warn': !hasChanges }">
          <MdIcon name="file" :size="13" />
          {{ hasChanges ? `${changedPaths.length} 个文件待提交` : '没有待提交的改动' }}
        </span>
        <span v-if="current?.ahead" class="md-git-chip">领先 {{ current.ahead }} 个提交</span>
        <span v-if="current?.behind" class="md-git-chip is-warn">落后 {{ current.behind }} 个提交</span>
      </section>

      <!-- 变更文件清单：这是这个面板存在的理由，放最显眼的位置 -->
      <section class="md-git-files">
        <h4 class="md-git-title">变更文件</h4>
        <ul v-if="changedPaths.length" class="md-git-list">
          <li v-for="path in changedPaths" :key="path" class="md-git-list__item" :title="path">
            <MdIcon name="file" :size="13" />
            <span class="md-git-list__path">{{ path }}</span>
          </li>
        </ul>
        <el-empty v-else-if="!loading" description="工作区干净，没有需要提交的文件" :image-size="52" />
      </section>

      <section class="md-git-message">
        <h4 class="md-git-title">提交说明</h4>
        <el-input
          v-model="message"
          type="textarea"
          :rows="3"
          :maxlength="MAX_MESSAGE"
          show-word-limit
          placeholder="用一句话说明这次改了什么，例如「补齐记忆碎片的三层结构」"
        />
        <p class="md-git-hint">{{ submitHint }}</p>
      </section>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading" @click="submit">
        <MdIcon name="arrow-up" :size="14" />{{ submitText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.md-git-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 14px;
}
.md-git-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--md-primary-soft);
  color: var(--md-primary);
  font-size: 12px;
  line-height: 20px;
}
.md-git-chip.is-warn {
  background: var(--md-warn-soft, #fdf6ec);
  color: var(--md-warn, #b88230);
}
.md-git-title {
  margin: 0 0 8px;
  color: var(--md-text-2);
  font-size: 12px;
  font-weight: 600;
}
/*
 * 清单要限高：云端库动辄几百个文件，全列出来会把提交说明挤到折叠线以下，
 * 而「看一眼有没有多带进一个文件」才是这里的真实需求。
 */
.md-git-list {
  max-height: 240px;
  margin: 0;
  padding: 6px;
  overflow: auto;
  list-style: none;
  border: 1px solid var(--md-border);
  border-radius: 8px;
}
.md-git-list__item {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 3px 6px;
  border-radius: 5px;
  color: var(--md-text-2);
  font-size: 13px;
  line-height: 22px;
}
.md-git-list__item:hover {
  background: var(--md-bg-soft, #f6f7f9);
}
/* 路径可能很长，尾部信息比头部更重要，所以截断放在左边 */
.md-git-list__path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.md-git-message {
  margin-top: 16px;
}
.md-git-hint {
  margin: 6px 0 0;
  color: var(--md-text-3);
  font-size: 12px;
  line-height: 1.6;
}
</style>
