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
import { computed, ref, watch } from 'vue'
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

/*
 * 加载失败要说出来。
 *
 * <p>不加这一层时，失败的图只剩浏览器给的「裂图 + alt」—— 而这里的 alt 正是文件名，
 * 看起来就像「这张图本来就是一段文字」，读者无从判断是图坏了还是自己点错了。
 * 后端遇到读不到的图会留占位说明，这里对齐同一套：把失败讲清楚，并把真实地址摆出来
 * 供排查（多半是前缀少了一段，或文件已不在库里）。</p>
 *
 * <p>必须 watch {@code path}：切换预览对象时组件是原地复用而不是重建（父层的
 * {@code v-if} 只从 false 变 true 一次），不重置的话，上一张的失败会一直挂在下一张上。</p>
 */
const failed = ref(false)
watch(() => props.path, () => { failed.value = false })
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
      <div v-if="failed" class="md-asset-view__fail">
        <Icon name="image" :size="24" />
        <strong>图片加载失败</strong>
        <p>文件可能已不在库里，或资源地址不对。</p>
        <code :title="src">{{ src }}</code>
      </div>
      <img v-else :src="src" :alt="name" class="md-zoomable" @error="failed = true">
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
.md-asset-view__fail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  max-width: 520px;
  padding: 20px 24px;
  border: 1px dashed var(--md-border);
  border-radius: 10px;
  background: var(--md-surface-1, transparent);
  color: var(--md-text-2);
  text-align: center;
}
.md-asset-view__fail strong {
  color: var(--md-text-1);
  font-size: 14px;
  font-weight: 600;
}
.md-asset-view__fail p {
  margin: 0;
  font-size: 13px;
}
/* 地址要能换行，长路径不能把卡片撑破；同时也方便直接选中复制去排查 */
.md-asset-view__fail code {
  max-width: 100%;
  font-size: 12px;
  overflow-wrap: anywhere;
}
</style>

