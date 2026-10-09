<script setup lang="ts">
/**
 * 知识库图标。
 *
 * <p>与 {@code Icon.vue} 分开是必须的：那边是 24 视框的**线性描边**图标集，而这是一个
 * **实心填充**图形，父级 svg 的 {@code fill="none" stroke="currentColor"} 恰好与它
 * 相反，硬塞进 {@code PATHS} 只会得到一个空框（路径被 fill:none 吃掉）或一圈多余的描边。</p>
 *
 * <p><b>形状是「带折角的一张纸」，不是「摊开的书」。</b>换掉的理由是尺寸：调用处
 * （工作区顶栏、门户卡片、阅读页头部）都把它放在 34px 的渐变方块里、实际只有 19px。
 * 摊开的书在这个尺寸下有书脊、页缝七八处细节，缩到 19px 全部糊在一起 ——
 * 远看就是一块彩色污渍，认不出是什么。带折角的纸只有一个外轮廓和一条斜边，
 * 19px 下轮廓完整，是能被读出来的最小细节量。</p>
 *
 * <p>不要为了「像个知识库」加回书本/书架这类多细节图形：这里的位置只有 19px，
 * 识别的优先级是「能认出是个图标」而不是「能认出是知识库」。</p>
 */
const PATH =
  'M5 2h8l6 6v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z'

withDefaults(defineProps<{ size?: number | string }>(), { size: 16 })
</script>

<template>
  <svg
    class="md-kb-glyph"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="currentColor"
    aria-hidden="true"
    focusable="false"
  >
    <path :d="PATH" />
  </svg>
</template>

<style scoped>
.md-kb-glyph { display: block; flex-shrink: 0; }
</style>
