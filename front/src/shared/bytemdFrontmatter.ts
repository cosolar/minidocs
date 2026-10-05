import type { BytemdPlugin } from 'bytemd'
import type { Root } from 'mdast'
import type { VFile } from 'vfile'

/**
 * 定位正文头部 YAML frontmatter 的结束行（1 基），没有则返回 0。
 *
 * <p>与后端 {@code FrontMatterParser} 同口径：首行必须恰好是 {@code ---}，结束符取其后
 * 首次出现的 {@code ---} 行。</p>
 */
function frontmatterEndLine(text: string): number {
  const normalized = text.replace(/\r\n/g, '\n')
  if (!normalized.startsWith('---')) return 0
  const firstLineEnd = normalized.indexOf('\n')
  if (firstLineEnd < 0 || normalized.slice(0, firstLineEnd).trim() !== '---') return 0
  const end = normalized.indexOf('\n---', firstLineEnd)
  if (end < 0) return 0
  return normalized.slice(0, end + 1).split('\n').length
}

/**
 * 预览里不渲染正文头部的 YAML frontmatter。
 *
 * <p>frontmatter 是文档属性（标题 / 标签 / 日期），编辑区必须原样保留，但预览管线不认识它，
 * 会把 {@code ---} 当成分隔线、把键值行当成段落与列表渲染出来，页首凭空多出一坨内容。
 * 这里在 remark 阶段按行号把开头整块摘掉——只作用于预览，CodeMirror 里的原文与保存内容都不动。</p>
 */
export function frontmatterStrip(): BytemdPlugin {
  return {
    remark: (processor) =>
      processor.use(() => (tree: Root, file: VFile) => {
        const endLine = frontmatterEndLine(String(file?.value ?? ''))
        if (!endLine) return
        // 整块落在 endLine 之前（含结束符那行）；遇到第一个越过结束行的节点就收手
        while (tree.children.length && (tree.children[0].position?.end?.line ?? 0) <= endLine) {
          tree.children.shift()
        }
      })
  }
}
