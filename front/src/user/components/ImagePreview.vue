<script setup lang="ts">
/**
 * 库内图片的预览面板。
 *
 * <p>目录树上的图片节点点开就是它。存在的理由是：markdown 里的图片能看，但读者
 * <b>没有入口</b>去看那些没被任何文档引用的图，也不知道图叫什么名、放在哪 ——
 * 图和文档混在一个库里时，这部分内容等于不存在。</p>
 *
 * <p>不自己实现缩放：{@code enhanceMarkdown} 已有成熟的灯箱（滚轮缩放 / 拖拽 / 双指 / 旋转），
 * 这里只负责「选中哪张图」与外壳，复用那套交互，避免出现第二份手势逻辑。</p>
 */
import { computed } from 'vue'
import Icon from '@/user/components/Icon.vue'

const props = defineProps<{
  /** 图片的库内相对路径，用于拼资源地址 */
  path: string
  name: string
  /** 资源前缀，形如 /minidocs/kb/{org}/{slug}/asset/ */
  assetPrefix: string
}>()

const emit = defineEmits<{ (e: 'close'): void }>()

/** 逐段编码：中文名与空格都要转义，否则地址栏里直接就是乱码 */
const src = computed(() => {
  const encoded = props.path.split('/').map((s) => encodeURIComponent(s)).join('/')
  return `${props.assetPrefix}${encoded}`
})
</script>

<template>
  <div class="md-asset-view" role="dialog" aria-modal="true" :aria-label="name">
    <div class="md-asset-view__bar">
      <Icon name="image" :size="15" />
      <span class="md-asset-view__name" :title="path">{{ name }}</span>
      <span class="md-spacer" />
      <a class="md-asset-view__act" :href="src" download :title="`下载 ${name}`">
        <Icon name="download" :size="14" />下载
      </a>
      <button type="button" class="md-asset-view__act" title="关闭" @click="emit('close')">
        <Icon name="close" :size="14" />
      </button>
    </div>
    <div class="md-asset-view__stage">
      <img :src="src" :alt="name" class="md-zoomable">
    </div>
  </div>
</template>

<style scoped>
/*
 * 占满编辑区/正文区的那一块，而不是弹层：图片是「选中项」不是「临时看一眼」，
 * 弹层会把人挡在树外面，尺寸还得跟视口较劲。占位反而能跟正文区一起滚动。
 */
.md-asset-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--md-surface-1, transparent);
}
.md-asset-view__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--md-border);
  color: var(--md-text-2);
  font-size: 13px;
}
.md-asset-view__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.md-asset-view__act {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--md-text-2);
  font-size: 13px;
  cursor: pointer;
  text-decoration: none;
}
.md-asset-view__act:hover {
  background: var(--md-surface-2);
  color: var(--md-text-1);
}
.md-asset-view__stage {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 18px;
  /* 深浅不一的棋盘底：透明 PNG / 浅色图才不会糊在白底上看不出边界 */
  background-image:
    linear-gradient(45deg, var(--md-surface-2) 25%, transparent 25%),
    linear-gradient(-45deg, var(--md-surface-2) 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, var(--md-surface-2) 75%),
    linear-gradient(-45deg, transparent 75%, var(--md-surface-2) 75%);
  background-size: 18px 18px;
  background-position: 0 0, 0 9px, 9px -9px, -9px 0;
}
.md-asset-view__stage img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: 6px;
  box-shadow: 0 2px 12px rgba(24, 29, 51, 0.12);
}
</style>

