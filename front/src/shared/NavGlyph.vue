<script setup lang="ts">
/**
 * 导航菜单图标渲染器：从生成的路径数据里取body 画出来。
 *
 * <p>单独一个组件而不是往 Icon.vue 的 PATHS 里塞：那是 24 视框的线条图标表，
 * 而这份数据有 240 条、每条自带 fill/stroke 设定，混进同一张表会让「单色描边」
 * 与「自带配色」两套渲染规则互相干扰。</p>
 *
 * <p>取色靠 currentColor：body 里 Tabler 已经写了stroke="currentColor"，所以明暗主题
 * 自动跟随，激活项变白、未激活项是灰的，与同一行文字同色。</p>
 */
import { computed } from 'vue'
import { GLYPHS, type Glyph } from '@/shared/navGlyphs.generated'

const props = defineProps<{
  /** 生成的图标名（tabler 的短名，如 book-open）；找不到时回退到 fallback */
  name?: string
  /** name查不到时用；仍查不到则不渲染（父级一般会先判好） */
  fallback?: string
  size?: number | string
}>()

const glyph = computed<Glyph | null>(() => {
  const hit = (props.name && GLYPHS[props.name]) || (props.fallback && GLYPHS[props.fallback])
  return hit || null
})

const px = computed(() => `${props.size ?? 15}px`)
</script>

<template>
  <svg
    v-if="glyph"
    class="md-nav-glyph"
    :style="{ width: px, height: px }"
    :viewBox="`0 0 ${glyph.w} ${glyph.h}`"
    aria-hidden="true"
    focusable="false"
    v-html="glyph.body"
  />
</template>

<style scoped>
.md-nav-glyph { display: block; flex-shrink: 0; overflow: visible; }
</style>
