/**
 * 正文浏览器端增强：代码高亮、代码工具栏、Admonition、KaTeX、Mermaid、图片灯箱。
 *
 * <p>由服务端渲染出的安全 HTML 直接 v-html 落地，这里只在其后做「锦上添花」的增强，
 * 增强失败不影响正文可读性（每一段都单独 try/catch）。</p>
 *
 * <p>门户阅读页 / 分享页走 {@link enhanceMarkdown}（全量增强）；后台编辑页的 bytemd 预览
 * 自带 gfm 与代码高亮，只需要补图表、灯箱与表格外壳，走 {@link enhancePreview}。</p>
 */
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import c from 'highlight.js/lib/languages/c'
import cpp from 'highlight.js/lib/languages/cpp'
import csharp from 'highlight.js/lib/languages/csharp'
import css from 'highlight.js/lib/languages/css'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import go from 'highlight.js/lib/languages/go'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import kotlin from 'highlight.js/lib/languages/kotlin'
import lua from 'highlight.js/lib/languages/lua'
import markdown from 'highlight.js/lib/languages/markdown'
import nginx from 'highlight.js/lib/languages/nginx'
import php from 'highlight.js/lib/languages/php'
import plaintext from 'highlight.js/lib/languages/plaintext'
import properties from 'highlight.js/lib/languages/properties'
import python from 'highlight.js/lib/languages/python'
import ruby from 'highlight.js/lib/languages/ruby'
import rust from 'highlight.js/lib/languages/rust'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import renderMathInElement from 'katex/contrib/auto-render'

const LANGUAGES = {
  bash, c, cpp, csharp, css, dockerfile, go, java, javascript, json, kotlin,
  lua, markdown, nginx, php, plaintext, properties, python, ruby, rust, sql,
  typescript, xml, yaml
}

/*
 * highlight.js/lib/core 只是内核，语言模块要显式注册才会生效。
 * 漏掉这一步的表现很隐蔽：hljs.highlight 会先 console.error 一句
 * 「Could not find the language 'java', did you forget to load/include a language module?」，
 * 再抛异常，而下面的 try/catch 把异常吞了——控制台刷满报错，代码块却全无高亮。
 */
for (const [name, definition] of Object.entries(LANGUAGES)) {
  hljs.registerLanguage(name, definition)
}

/* ------------------------------------------------------------------ 代码块 */
function enhanceCodeBlocks(root: ParentNode) {
  root.querySelectorAll<HTMLPreElement>('pre > code').forEach((code) => {
    const pre = code.parentElement as HTMLPreElement
    if (!pre) return
    const match = /language-([\w+#-]+)/.exec(code.className || '')
    const lang = match ? match[1].toLowerCase() : ''

    // 用 getLanguage 判存在，而不是查自己那张表：js / ts / py / sh / yml / c++ 这类别名
    // 由各语言模块自带，自己维护一份迟早和 highlight.js 的清单对不上。
    // 顺带也就跳过了 mermaid、math 这些不是编程语言的围栏（它们由下面的渲染接管）。
    if (lang && hljs.getLanguage(lang)) {
      try {
        code.innerHTML = hljs.highlight(code.textContent || '', { language: lang }).value
      } catch {
        /* 高亮失败时保留原文 */
      }
    }

    if (pre.parentElement?.classList.contains('md-code-wrap')) return

    const wrap = document.createElement('div')
    wrap.className = 'md-code-wrap'
    const head = document.createElement('div')
    head.className = 'md-code-head'
    head.innerHTML =
      '<span class="md-code-dots"><i></i><i></i><i></i></span>' +
      `<span class="md-code-lang">${lang || 'text'}</span>` +
      '<button type="button" class="md-code-copy">复制</button>'
    pre.parentElement?.insertBefore(wrap, pre)
    wrap.appendChild(head)
    wrap.appendChild(pre)

    head.querySelector('.md-code-copy')?.addEventListener('click', async (event) => {
      const button = event.currentTarget as HTMLButtonElement
      try {
        await navigator.clipboard.writeText(code.textContent || '')
        button.textContent = '已复制'
      } catch {
        button.textContent = '复制失败'
      }
      setTimeout(() => (button.textContent = '复制'), 1600)
    })
  })
}

/* ------------------------------------------------------------------ Admonition */
const ADMONITIONS: Record<string, { label: string; icon: string; modifier: string }> = {
  NOTE: { label: '提示', icon: 'ℹ', modifier: '' },
  TIP: { label: '技巧', icon: '✦', modifier: 'md-admonition--tip' },
  WARNING: { label: '警告', icon: '⚠', modifier: 'md-admonition--warning' },
  IMPORTANT: { label: '重要', icon: '★', modifier: 'md-admonition--important' },
  CAUTION: { label: '注意', icon: '⚑', modifier: 'md-admonition--caution' }
}

function enhanceAdmonitions(root: ParentNode) {
  root.querySelectorAll<HTMLElement>('blockquote').forEach((quote) => {
    const first = quote.querySelector('p')
    if (!first) return
    const match = /^\s*\[!(\w+)\]\s*/.exec(first.textContent || '')
    if (!match) return
    const config = ADMONITIONS[match[1].toUpperCase()]
    if (!config) return

    first.innerHTML = (first.innerHTML || '').replace(/^\s*\[!\w+\]\s*(<br\s*\/?>)?/, '')
    if (!first.textContent?.trim()) first.remove()

    const box = document.createElement('div')
    box.className = `md-admonition ${config.modifier}`.trim()
    const title = document.createElement('div')
    title.className = 'md-admonition__title'
    title.textContent = `${config.icon} ${config.label}`
    box.appendChild(title)
    quote.parentElement?.insertBefore(box, quote)
    while (quote.firstChild) box.appendChild(quote.firstChild)
    quote.remove()
  })
}

/* ------------------------------------------------------------------ 表格 */
/**
 * 给每张表格套一层 `.md-table-wrap` 外壳。
 *
 * <p>外壳负责圆角、边框、阴影与横向滚动，表格自己只管排版。圆角必须由外层的 overflow 裁剪：
 * 直接画在 table 上收不住 —— 表头底色与分隔线会顺着表格的布局盒溢出圆角，四角变成方的，
 * 而给 table 加 overflow 又会让它在窄屏里被截断而不是滚动。</p>
 *
 * <p>阅读页 / 分享页与后台预览区都走这里，两边的表格观感因此完全一致；重复调用靠父节点判断去重
 * （后台预览会反复跑后处理）。</p>
 */
function enhanceTables(root: ParentNode) {
  root.querySelectorAll<HTMLTableElement>('table').forEach((table) => {
    if (table.parentElement?.classList.contains('md-table-wrap')) return
    const wrap = document.createElement('div')
    wrap.className = 'md-table-wrap'
    table.parentElement?.insertBefore(wrap, table)
    wrap.appendChild(table)
  })
}

/* ------------------------------------------------------------------ 公式与图表 */
function renderFormulas(root: ParentNode) {
  // ```math 围栏先落成块级公式容器，再交给 auto-render 处理
  root.querySelectorAll<HTMLElement>('pre > code.language-math').forEach((block) => {
    const holder = document.createElement('div')
    holder.className = 'katex-display'
    holder.textContent = block.textContent || ''
    const container = block.closest('.md-code-wrap') || block.parentElement
    container?.replaceWith(holder)
  })
  try {
    renderMathInElement(root as HTMLElement, {
      delimiters: [
        { left: '$$', right: '$$', display: true },
        { left: '\\[', right: '\\]', display: true },
        { left: '$', right: '$', display: false },
        { left: '\\(', right: '\\)', display: false }
      ],
      ignoredTags: ['pre', 'code', 'script', 'style', 'textarea', 'option'],
      throwOnError: false
    })
  } catch {
    /* 忽略公式渲染异常 */
  }
}

async function renderMermaid(root: ParentNode, dark: boolean) {
  // 同一批代码块可能被连着增强好几轮（预览区一次加载就会跑好几遍后处理），
  // 先在同步阶段认领，免得两轮并发去渲染同一块——mermaid 的渲染状态是全局的，
  // 并发进来会互相踩，最后给你一张「Syntax error in text」的报错图。
  const blocks = Array.from(root.querySelectorAll<HTMLElement>('pre > code.language-mermaid')).filter(
    (block) => {
      if (block.dataset.mermaidQueued) return false
      block.dataset.mermaidQueued = '1'
      return true
    }
  )
  if (blocks.length === 0) return
  try {
    // Mermaid 体积大，按需动态加载（未命中 mermaid 代码块时不下载）
    const mermaid = (await import('mermaid')).default
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: dark ? 'dark' : 'default',
      fontFamily: 'inherit',
      // 语法出错时让 render 抛出来，交给下面的兜底把原始代码留在页面上；
      // 否则 mermaid 会塞一张自带的「Syntax error」报错图，对读者没有半点信息量
      suppressErrorRendering: true
    })
    let index = 0
    for (const block of blocks) {
      const source = block.textContent || ''
      const holder = document.createElement('div')
      holder.className = 'mermaid-block'
      try {
        const { svg } = await mermaid.render(`md-mermaid-${Date.now()}-${index++}`, source)
        holder.innerHTML = svg
      } catch {
        holder.textContent = source
      }
      const container = block.closest('.md-code-wrap') || block.parentElement
      container?.replaceWith(holder)
    }
  } catch {
    /* 忽略图表渲染异常 */
  }
}

/* ------------------------------------------------------------------ 图片灯箱 */
const MIN_SCALE = 0.2
const MAX_SCALE = 12
const ZOOM_STEP = 1.25

interface LightboxMedia {
  src: string
  alt: string
  /**
   * 这份媒体是不是 SVG（Mermaid 图等）。
   *
   * <p>SVG 的文字与连线是照浅色底设计的，压在灯箱的暗色幕布上会糊成一片，
   * 所以它要单独垫一层白底；位图自己带底色，不需要。</p>
   */
  svg?: boolean
}

/**
 * 把内联 SVG（Mermaid 图等）转成 data URL。
 *
 * <p>不直接把节点搬进灯箱：Mermaid 的配色写在 SVG 自带的 {@code <style>} 里，选择器是
 * {@code #id}，同一个 id 在文档里出现两次会互相串味。转成 data URL 后 SVG 自成一个文档，
 * id 不再冲突，灯箱里也能和普通图片走同一套缩放/平移逻辑。</p>
 */
function svgToDataUrl(svg: SVGSVGElement): string {
  const clone = svg.cloneNode(true) as SVGSVGElement
  const rect = svg.getBoundingClientRect()
  if (!clone.getAttribute('xmlns')) clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  // 内联时尺寸靠 CSS 撑开，进 <img> 后得给个固有尺寸，否则按 300×150 的默认值渲染
  clone.setAttribute('width', String(Math.max(1, Math.round(rect.width))))
  clone.setAttribute('height', String(Math.max(1, Math.round(rect.height))))
  // Mermaid 会在根节点写死 max-width，留在 <img> 里会把图缩成一小条
  clone.style.removeProperty('max-width')
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(new XMLSerializer().serializeToString(clone))}`
}

/** 打开灯箱：滚轮/按钮缩放、拖拽与双指平移、旋转、复位、Esc 关闭。 */
function openLightbox(media: LightboxMedia) {
  const box = document.createElement('div')
  box.className = 'md-lightbox'
  box.setAttribute('role', 'dialog')
  box.setAttribute('aria-modal', 'true')
  box.innerHTML =
    '<div class="md-lightbox__stage"><img class="md-lightbox__media" alt=""></div>' +
    '<span class="md-lightbox__hint">滚轮缩放 · 拖拽平移 · 双击复位 · Esc 关闭</span>' +
    '<div class="md-lightbox__bar">' +
    '<button type="button" class="md-lightbox__btn" data-act="out" title="缩小">−</button>' +
    '<span class="md-lightbox__scale">100%</span>' +
    '<button type="button" class="md-lightbox__btn" data-act="in" title="放大">+</button>' +
    '<span class="md-lightbox__sep"></span>' +
    '<button type="button" class="md-lightbox__btn" data-act="rotate" title="旋转 90°">↻</button>' +
    '<button type="button" class="md-lightbox__btn" data-act="reset" title="复位">复位</button>' +
    '</div>' +
    '<button type="button" class="md-lightbox__close" aria-label="关闭">×</button>'

  const stage = box.querySelector('.md-lightbox__stage') as HTMLElement
  const image = box.querySelector('.md-lightbox__media') as HTMLImageElement
  const scaleLabel = box.querySelector('.md-lightbox__scale') as HTMLElement
  // src / alt 走 DOM 属性赋值，别拼进 innerHTML —— 图片地址来自正文，可能是用户内容
  image.src = media.src
  image.alt = media.alt
  if (media.svg) image.classList.add('md-lightbox__media--svg')

  let scale = 1
  let tx = 0
  let ty = 0
  let rot = 0

  const paint = () => {
    image.style.transform = `translate(${tx}px, ${ty}px) rotate(${rot}deg) scale(${scale})`
    scaleLabel.textContent = `${Math.round(scale * 100)}%`
  }

  // 图片在 stage 里居中，且 transform-origin 为 center，因此「布局中心」恒等于 stage 中心
  const stageCenter = () => {
    const rect = stage.getBoundingClientRect()
    return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
  }

  /** 以某个屏幕点为锚缩放：该点下的图内位置在缩放前后保持不动 */
  const zoomAt = (clientX: number, clientY: number, next: number) => {
    const target = Math.min(MAX_SCALE, Math.max(MIN_SCALE, next))
    if (target === scale) return
    const center = stageCenter()
    const dx = clientX - center.x
    const dy = clientY - center.y
    const rad = (rot * Math.PI) / 180
    const cos = Math.cos(rad)
    const sin = Math.sin(rad)
    // 反解光标下的图内坐标：先去掉平移，再反旋转、除以当前缩放
    const lx = ((dx - tx) * cos + (dy - ty) * sin) / scale
    const ly = (-(dx - tx) * sin + (dy - ty) * cos) / scale
    scale = target
    // 让同一个图内点继续落在光标下：t = d - R(r)·(l·s)
    const px = lx * scale
    const py = ly * scale
    tx = dx - (px * cos - py * sin)
    ty = dy - (px * sin + py * cos)
    paint()
  }

  const reset = () => {
    scale = 1
    tx = 0
    ty = 0
    rot = 0
    paint()
  }

  const close = () => {
    document.removeEventListener('keydown', onKey)
    document.body.classList.remove('md-lightbox-open')
    box.remove()
  }

  const onKey = (event: KeyboardEvent) => {
    if (event.key === 'Escape') {
      close()
      return
    }
    const center = stageCenter()
    if (event.key === '+' || event.key === '=') zoomAt(center.x, center.y, scale * ZOOM_STEP)
    else if (event.key === '-' || event.key === '_') zoomAt(center.x, center.y, scale / ZOOM_STEP)
    else if (event.key === '0') reset()
    else if (event.key === 'r' || event.key === 'R') {
      rot = (rot + 90) % 360
      paint()
    }
  }

  /* 指针事件同时覆盖鼠标与触摸：单指拖拽平移，双指捏合缩放 */
  const pointers = new Map<number, { x: number; y: number }>()
  let pinchDistance = 0
  let pinchMid = { x: 0, y: 0 }

  const onPointerDown = (event: PointerEvent) => {
    if (event.pointerType === 'mouse' && event.button !== 0) return
    image.setPointerCapture(event.pointerId)
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
    box.classList.add('is-grabbing')
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()]
      pinchDistance = Math.hypot(a.x - b.x, a.y - b.y)
      pinchMid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
    }
  }

  const onPointerMove = (event: PointerEvent) => {
    const previous = pointers.get(event.pointerId)
    if (!previous) return
    const current = { x: event.clientX, y: event.clientY }
    pointers.set(event.pointerId, current)

    if (pointers.size >= 2) {
      const [a, b] = [...pointers.values()]
      const distance = Math.hypot(a.x - b.x, a.y - b.y)
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
      if (pinchDistance > 0) zoomAt(mid.x, mid.y, scale * (distance / pinchDistance))
      tx += mid.x - pinchMid.x
      ty += mid.y - pinchMid.y
      pinchDistance = distance
      pinchMid = mid
      paint()
      return
    }

    tx += current.x - previous.x
    ty += current.y - previous.y
    paint()
  }

  const onPointerUp = (event: PointerEvent) => {
    pointers.delete(event.pointerId)
    if (pointers.size < 2) pinchDistance = 0
    if (pointers.size === 0) box.classList.remove('is-grabbing')
  }

  box.addEventListener('click', (event) => {
    const target = event.target as HTMLElement
    if (target === box || target === stage || target.classList.contains('md-lightbox__close')) close()
  })
  box.addEventListener(
    'wheel',
    (event) => {
      event.preventDefault()
      zoomAt(event.clientX, event.clientY, scale * (event.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP))
    },
    { passive: false }
  )
  box.querySelectorAll<HTMLButtonElement>('.md-lightbox__btn').forEach((button) => {
    button.addEventListener('click', (event) => {
      event.stopPropagation()
      const center = stageCenter()
      const act = button.dataset.act
      if (act === 'in') zoomAt(center.x, center.y, scale * ZOOM_STEP)
      else if (act === 'out') zoomAt(center.x, center.y, scale / ZOOM_STEP)
      else if (act === 'rotate') {
        rot = (rot + 90) % 360
        paint()
      } else if (act === 'reset') reset()
    })
  })
  image.addEventListener('pointerdown', onPointerDown)
  image.addEventListener('pointermove', onPointerMove)
  image.addEventListener('pointerup', onPointerUp)
  image.addEventListener('pointercancel', onPointerUp)
  image.addEventListener('dblclick', reset)
  document.addEventListener('keydown', onKey)

  document.body.classList.add('md-lightbox-open')
  document.body.appendChild(box)
}

/**
 * 给正文里的图片与内联 SVG 挂上灯箱。
 *
 * <p>会被反复调用（预览区一次加载要跑好几轮后处理），靠 {@code dataset} 标记去重，
 * 免得一个元素叠上好几层监听器，点一下弹出好几个灯箱。</p>
 */
function initLightbox(root: ParentNode) {
  root.querySelectorAll<HTMLImageElement>('img').forEach((img) => {
    markBrokenImage(img)
    // 图片外面套了链接时，点击应当去跳转，别把链接抢掉
    if (img.closest('a')) return
    // 已经确认加载失败的破图不值得放大
    if (img.complete && img.naturalWidth === 0) return
    bindLightbox(img, () => ({ src: img.currentSrc || img.src, alt: img.alt || '' }))
  })

  root.querySelectorAll<SVGSVGElement>('svg').forEach((svg) => {
    if (svg.closest('a, button, .md-lightbox')) return
    // 小于 80px 的多半是正文里的小图标，放大没有意义
    if (svg.getBoundingClientRect().width < 80) return
    bindLightbox(svg, () => ({ src: svgToDataUrl(svg), alt: '', svg: true }))
  })
}

/**
 * 裂图兜底：把加载失败的 {@code <img>} 换成与后端同款的占位块。
 *
 * <p>为什么需要：资源端点对「文件不存在」返回 404，浏览器的默认表现是显示浏览器自带的
 * 破图图标 —— 作者没写 alt 时那个图标旁边什么都没有，读者看不出这里原本该有张图。
 * 换成带虚线框和悬停说明的占位，缺图就变成一件「看得见的事」。</p>
 *
 * <p>已用 {@code dataset} 去重：后处理会被反复调用（预览区每轮重渲染都跑一遍），
 * 不去重会套出一层又一层的占位块。</p>
 */
function markBrokenImage(img: HTMLImageElement) {
  const fail = () => {
    if (img.dataset.brokenMarked) return
    img.dataset.brokenMarked = '1'
    const tip = document.createElement('span')
    tip.className = 'md-img-missing'
    tip.title = '图片加载失败：文件可能已被删除或改名'
    tip.textContent = img.alt || '图片无法显示'
    img.replaceWith(tip)
  }
  // 缓存里已经失败的图不会再触发 onerror，得单独补一刀
  if (img.complete && img.naturalWidth === 0) {
    fail()
    return
  }
  img.addEventListener('error', fail, { once: true })
}

function bindLightbox(el: Element, media: () => LightboxMedia) {
  const node = el as HTMLElement
  if (node.dataset.lightboxBound) return
  node.dataset.lightboxBound = '1'
  node.classList.add('md-zoomable')
  node.addEventListener('click', () => openLightbox(media()))
}

/* ------------------------------------------------------------------ 对外入口 */
/** 当前主题是否为暗色：Mermaid 的主题要跟着站点走 */
function isDarkTheme() {
  return document.documentElement.getAttribute('data-theme') === 'dark'
}

/**
 * 全量增强：阅读页 / 分享页用。
 *
 * @param root 正文容器（通常是 .md-markdown 所在元素）
 */
export async function enhanceMarkdown(root: HTMLElement | null | undefined): Promise<void> {
  if (!root) return
  const markdownRoot = root.classList.contains('md-markdown') ? root : root.querySelector('.md-markdown')
  if (!markdownRoot) return

  enhanceAdmonitions(markdownRoot)
  enhanceTables(markdownRoot)
  enhanceCodeBlocks(markdownRoot)
  await renderMermaid(markdownRoot, isDarkTheme())
  renderFormulas(markdownRoot)
  initLightbox(markdownRoot)
}

/**
 * 预览增强：后台编辑页的 bytemd 预览用。
 *
 * <p>bytemd 预览自带 gfm 与代码高亮，缺的是 ```mermaid 出图、图片/SVG 灯箱与表格外壳；全量增强还会给
 * 代码块再包一层工具条、做 Admonition 与公式，那些在预览区属于多余改动，所以这里只调
 * 图表、灯箱与表格三段。</p>
 *
 * <p>替换过的代码块已经变成图表容器，再次调用时选不中，因此预览反复重渲染也不会重复出图；
 * 灯箱靠元素上的 dataset 标记去重，表格外壳靠父节点判断，同样不会叠出第二层壳。</p>
 */
export async function enhancePreview(root: HTMLElement | null | undefined): Promise<void> {
  if (!root) return
  enhanceTables(root)
  await renderMermaid(root, isDarkTheme())
  initLightbox(root)
}
