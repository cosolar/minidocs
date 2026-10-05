<script setup lang="ts">
import { computed } from 'vue'
import { can } from '@/shared/api/caps'
import MdIcon from '@/admin/components/MdIcon.vue'
import type { KbActionName, KbVO } from '@/shared/api/types'

/**
 * 知识库一行的动作集，网格卡片与列表表格共用。
 *
 * <p>抽出来是因为两种布局要写同一批按钮与同一套门禁（{@code myPermissions}）：抄两遍的话，
 * 下次加一个动作就会只加在其中一边，而「列表里能删、网格里不能删」这种差异没人会当成 bug 去查。
 * 判定仍然全部交给 {@code can}，这里不重抄权限规则（规范 §2.6）。</p>
 *
 * <p>{@code dense} 只改外观：表格里用链接式按钮，一行塞得下；卡片里用普通按钮，点击目标更大。</p>
 */
const props = defineProps<{ kb: KbVO; dense?: boolean }>()

defineEmits<{
  workspace: []
  share: []
  edit: []
  perms: []
  favorite: []
  copy: []
  remove: []
}>()

const kbCan = (action: KbActionName) => can(props.kb.myPermissions, action)
const showPerms = computed(() => kbCan('KB_MEMBER_MANAGE') || kbCan('KB_SET_VISIBILITY'))
</script>

<template>
  <!--
    包一层：Element 给相邻 .el-button 加 margin-left:12px，与容器的 gap 叠起来会把五个动作
    挤到折行。这里统一归零，间距只由 .md-row-actions 的 gap 决定。

    两种形态各自一套皮肤：表格里是链接式文字（一行塞得下、不抢视觉），卡片里是
    「实心主行动 + 一排幽灵按钮」—— 五个描边按钮并排会显得又重又碎。
  -->
  <div class="md-row-actions" :class="dense ? 'is-dense' : 'is-card'">
    <el-button :link="dense" size="small" type="primary" @click="$emit('workspace')">
      <MdIcon name="arrow-right" :size="14" />进入
    </el-button>
    <el-button v-if="kbCan('SHARE_CREATE')" :link="dense" size="small" @click="$emit('share')">
      <MdIcon name="share" :size="14" />分享
    </el-button>
    <el-button v-if="kbCan('KB_EDIT_META')" :link="dense" size="small" @click="$emit('edit')">
      <MdIcon name="edit" :size="14" />编辑
    </el-button>
    <el-button v-if="showPerms" :link="dense" size="small" @click="$emit('perms')">
      <MdIcon name="key" :size="14" />权限
    </el-button>
    <el-dropdown placement="bottom-end" trigger="click">
      <el-button :link="dense" size="small">
        <MdIcon v-if="dense" name="more" :size="14" />
        <span>更多</span>
        <MdIcon v-if="!dense" name="chevron-down" :size="12" class="md-row-actions__caret" />
      </el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item @click="$emit('favorite')">
            <MdIcon name="star" :size="14" />{{ kb.favored ? '取消收藏' : '收藏' }}
          </el-dropdown-item>
          <el-dropdown-item @click="$emit('copy')">
            <MdIcon name="link" :size="14" />复制阅读页链接
          </el-dropdown-item>
          <el-dropdown-item v-if="kbCan('KB_DELETE')" divided @click="$emit('remove')">
            <MdIcon name="trash" :size="14" />删除
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.md-row-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-width: 0; }
.md-row-actions :deep(.el-button + .el-button) { margin-left: 0; }

/* ---------------------------------------------------------------- 卡片形态 */
.md-row-actions.is-card { gap: 4px; }
.md-row-actions.is-card :deep(.el-button) {
  height: 30px; padding: 0 11px;
  border-radius: 8px;
  font-size: 12.5px; font-weight: 500;
  /* 幽灵态：默认无底无框，靠悬停底色提示可点，避免一排描边按钮把卡片压碎 */
  --el-button-bg-color: transparent;
  --el-button-border-color: transparent;
  --el-button-text-color: var(--md-text-2);
  --el-button-hover-bg-color: var(--md-surface-2);
  --el-button-hover-border-color: transparent;
  --el-button-hover-text-color: var(--md-text);
  --el-button-active-bg-color: var(--md-surface-2);
  --el-button-active-border-color: transparent;
  --el-button-active-text-color: var(--md-text);
  --el-button-disabled-bg-color: transparent;
  --el-button-disabled-border-color: transparent;
  --el-button-disabled-text-color: var(--md-text-3);
}
/* 主行动实心：整张卡片只有它一处重色，视线自然落上去 */
.md-row-actions.is-card :deep(.el-button--primary) {
  padding: 0 14px;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(59, 108, 246, .32);
  --el-button-bg-color: var(--md-primary);
  --el-button-border-color: var(--md-primary);
  --el-button-text-color: #fff;
  --el-button-hover-bg-color: var(--md-primary-hover);
  --el-button-hover-border-color: var(--md-primary-hover);
  --el-button-hover-text-color: #fff;
  --el-button-active-bg-color: var(--md-primary-hover);
  --el-button-active-border-color: var(--md-primary-hover);
  --el-button-active-text-color: #fff;
}

/* 尾随图标：抵消全局 .el-button .md-icon 的右间距，改成左侧留一口气 */
.md-row-actions__caret { margin-left: 3px; margin-right: 0; opacity: .6; }
</style>

