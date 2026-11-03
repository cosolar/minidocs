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
    「实心主行动 + 一排幽灵图标按钮」—— 五颗带文字的按钮并排会把四列卡片挤到折行，
    而图标按钮高度仍是 30px，点击目标没有变小，含义由 title / aria-label 兜底。
  -->
  <div class="md-row-actions" :class="dense ? 'is-dense' : 'is-card'">
    <el-button :link="dense" size="small" type="primary" @click="$emit('workspace')">
      <MdIcon name="arrow-right" :size="14" />进入
    </el-button>
    <el-button
      v-if="kbCan('SHARE_CREATE')"
      class="md-action md-action--share"
      :link="dense"
      size="small"
      :title="dense ? undefined : '分享'"
      :aria-label="dense ? undefined : '分享'"
      @click="$emit('share')"
    >
      <MdIcon name="share" :size="15" /><span v-if="dense">分享</span>
    </el-button>
    <el-button
      v-if="kbCan('KB_EDIT_META')"
      class="md-action md-action--edit"
      :link="dense"
      size="small"
      :title="dense ? undefined : '编辑知识库'"
      :aria-label="dense ? undefined : '编辑知识库'"
      @click="$emit('edit')"
    >
      <MdIcon name="edit" :size="15" /><span v-if="dense">编辑</span>
    </el-button>
    <el-button
      v-if="showPerms"
      class="md-action md-action--perms"
      :link="dense"
      size="small"
      :title="dense ? undefined : '权限与名单'"
      :aria-label="dense ? undefined : '权限与名单'"
      @click="$emit('perms')"
    >
      <MdIcon name="key" :size="15" /><span v-if="dense">权限</span>
    </el-button>
    <el-dropdown placement="bottom-end" trigger="click">
      <el-button class="md-action md-action--more" :link="dense" size="small" title="更多操作" aria-label="更多操作">
        <MdIcon name="more" :size="15" />
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
/* 表格里的动作必须一行：换行时「更多」会单独掉到第二行，看着像一条孤立的分隔符 */
.md-row-actions.is-dense { gap: 2px; flex-wrap: nowrap; }
/* 主行动（进入）常驻品牌色：表格里其余动作是安静灰字，需要有一个明确的入口 */
.md-row-actions.is-dense :deep(.el-button--primary) {
  --el-button-text-color: var(--md-primary);
  --el-button-hover-text-color: var(--md-primary-hover);
  --el-button-hover-link-text-color: var(--md-primary-hover);
  font-weight: 600;
}

/* ---------------------------------------------------------------- 卡片形态 */
/* --act 挂在容器上让按钮继承，具体动作再用同名类覆盖；默认值落在「更多」的灰蓝 */
.md-row-actions.is-card { gap: 4px; flex-wrap: nowrap; --act: #64748b; }
.md-row-actions.is-card :deep(.el-button) {
  height: 36px; padding: 0 9px;
  border-radius: 10px;
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
/* 图标按钮：撑成正方形，抹掉全局 .el-button .md-icon 的右间距，图标才会真正居中 */
.md-row-actions.is-card :deep(.el-button:not(.el-button--primary)) {
  width: 36px; padding: 0; justify-content: center;
}
.md-row-actions.is-card :deep(.el-button:not(.el-button--primary) .md-icon) { margin-right: 0; }
/*
 * 次级动作各给一色，但都用低饱和底 + 同色图标：一眼能分清「分享 / 编辑 / 权限 / 更多」，
 * 又不会像四颗实心按钮那样把卡片底栏变成色板。颜色由 --act 驱动，深色主题靠
 * color-mix 与 --md-text 自适应，不必再写一套暗色值。
 */
.md-row-actions.is-card :deep(.el-button.md-action) {
  --el-button-bg-color: color-mix(in srgb, var(--act) 18%, transparent);
  --el-button-border-color: color-mix(in srgb, var(--act) 30%, transparent);
  --el-button-text-color: color-mix(in srgb, var(--act) 88%, var(--md-text));
  --el-button-hover-bg-color: color-mix(in srgb, var(--act) 26%, transparent);
  --el-button-hover-border-color: color-mix(in srgb, var(--act) 44%, transparent);
  --el-button-hover-text-color: color-mix(in srgb, var(--act) 94%, var(--md-text));
  --el-button-active-bg-color: color-mix(in srgb, var(--act) 32%, transparent);
  --el-button-active-border-color: color-mix(in srgb, var(--act) 50%, transparent);
  --el-button-active-text-color: color-mix(in srgb, var(--act) 96%, var(--md-text));
  --el-button-disabled-bg-color: color-mix(in srgb, var(--act) 8%, transparent);
  --el-button-disabled-border-color: transparent;
  --el-button-disabled-text-color: var(--md-text-3);
}
.md-row-actions.is-card :deep(.el-button.md-action--share) { --act: #0ea5e9; }
.md-row-actions.is-card :deep(.el-button.md-action--edit) { --act: #f59e0b; }
.md-row-actions.is-card :deep(.el-button.md-action--perms) { --act: #8b5cf6; }
.md-row-actions.is-card :deep(.el-button.md-action--more) { --act: #64748b; }
.md-row-actions.is-card :deep(.el-button.md-action:focus-visible) {
  outline: 2px solid color-mix(in srgb, var(--act) 70%, transparent);
  outline-offset: 1px;
}
/* 主行动实心：整张卡片只有它一处重色，视线自然落上去 */
.md-row-actions.is-card :deep(.el-button--primary) {
  height: 36px; padding: 0 13px;
  border-radius: 10px;
  font-weight: 600;
  background: linear-gradient(135deg, var(--md-primary), var(--md-primary-hover));
  box-shadow: 0 2px 6px color-mix(in srgb, var(--md-primary) 32%, transparent);
  --el-button-bg-color: transparent;
  --el-button-border-color: transparent;
  --el-button-text-color: #fff;
  --el-button-hover-bg-color: transparent;
  --el-button-hover-border-color: transparent;
  --el-button-hover-text-color: #fff;
  --el-button-active-bg-color: transparent;
  --el-button-active-border-color: transparent;
  --el-button-active-text-color: #fff;
}
.md-row-actions.is-card :deep(.el-button--primary:hover) {
  box-shadow: 0 3px 9px color-mix(in srgb, var(--md-primary) 40%, transparent);
}
.md-row-actions.is-card :deep(.el-button--primary .md-icon) { margin-right: 5px; }
</style>

