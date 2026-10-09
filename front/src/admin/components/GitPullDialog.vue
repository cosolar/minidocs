<script setup lang="ts">
/**
 * 拉取方式选择：合并拉取 / 强制覆盖本地。
 *
 * <p>为什么不用 {@code ElMessageBox.confirm}：强制覆盖会丢掉作者的未提交改动，
 * 这是不可逆的。消息框只能放一段文字，而这里需要把<b>会丢哪些文件</b>列出来 ——
 * 「确定要丢弃 3 个文件的改动吗」和「确定要丢弃 A.md、B.md、C.md 的改动吗」
 * 是两种后果完全不同的确认。</p>
 *
 * <p>合并拉取是默认项，且默认聚焦在它上面：多数时候用户要的只是「更新一下」，
 * 回车就该走安全的那条路。</p>
 */
import { computed, ref, watch } from 'vue'
import MdIcon from '@/admin/components/MdIcon.vue'
import type { GitStatusVO } from '@/shared/api/types'

const props = defineProps<{
  /** 当前工作副本状态，用来列出将被丢弃的文件 */
  status: GitStatusVO | null
}>()

const emit = defineEmits<{
  close: []
  pick: [force: boolean]
}>()

/** 将被丢弃的相对路径；后端 changedPaths 已把已跟踪改动与未跟踪文件合并成一份平铺清单 */
const dropped = computed(() => props.status?.changedPaths || [])

const dirty = computed(() => dropped.value.length > 0)

/** 默认选合并：多数时候用户要的只是「更新一下」，回车就该走安全的那条路 */
const forcePicked = ref(false)

/*
 * 工作区干净时不存在「丢弃」这个选项，面板关闭期间状态可能已经变了（别人推了提交、
 * 或者作者自己点了提交）。若那时 force 仍为 true，就会出现「面板上没有任何一项被选中、
 * 却执行了强制覆盖」—— 那是最难解释的一种错位。
 */
watch(dirty, (now) => {
  if (!now) forcePicked.value = false
}, { immediate: true })
</script>

<template>
  <el-dialog
    :model-value="true"
    title="拉取远程更新"
    width="480px"
    :close-on-click-modal="true"
    @close="emit('close')"
  >
    <div class="md-pullpick">
      <!--
        工作区干净时不提「丢弃」这回事：没有要丢的东西，给一个红色的破坏性选项
        只会让人以为点错了要出事。
      -->
      <p v-if="!dirty" class="md-pullpick__lead">
        当前工作区没有未提交的改动，两种方式结果相同。
      </p>

      <button
        type="button"
        class="md-pullpick__opt"
        :class="{ 'is-on': !forcePicked }"
        @click="forcePicked = false"
      >
        <span class="md-pullpick__mark"><MdIcon name="check" :size="13" /></span>
        <span class="md-pullpick__body">
          <b>合并拉取</b>
          <em>把远程的新提交合进来，本地改动原样保留；遇到冲突会中止并提示你处理。</em>
        </span>
      </button>

      <button
        v-if="dirty"
        type="button"
        class="md-pullpick__opt md-pullpick__opt--danger"
        :class="{ 'is-on': forcePicked }"
        @click="forcePicked = true"
      >
        <span class="md-pullpick__mark"><MdIcon name="check" :size="13" /></span>
        <span class="md-pullpick__body">
          <b>强制覆盖本地</b>
          <em>
            先把工作区恢复到上次提交的状态，再拉取。下列
            <strong>{{ dropped.length }}</strong>
            处未提交的改动会永久丢失，无法找回。
          </em>
        </span>
      </button>

      <details v-if="dirty" class="md-pullpick__list">
        <summary>将被丢弃的文件</summary>
        <ul>
          <li v-for="path in dropped" :key="path"><code>{{ path }}</code></li>
        </ul>
      </details>
    </div>

    <template #footer>
      <button type="button" class="md-btn" @click="emit('close')">取消</button>
      <button
        type="button"
        class="md-btn md-btn--primary"
        @click="emit('pick', forcePicked)"
      >
        {{ forcePicked ? '强制覆盖并拉取' : '合并拉取' }}
      </button>
    </template>
  </el-dialog>
</template>