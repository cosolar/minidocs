/**
 * 主题包模板（管理端「下载模板」与 docs/THEMES.md 共用）。
 *
 * <p>这是一份<b>能直接装上就有效果</b>的包：核心色取自 MiniDocs 默认主题，结构令牌也填了默认值。
 * 作者改几个色值就能得到一套新主题，不必从空对象猜令牌名。</p>
 *
 * <p>契约（后端 ThemeServiceImpl 会逐条校验，前端 readerTheme.ts 再筛一遍）：</p>
 * <ul>
 *   <li>{@code light} 与 {@code dark} 必须定义<b>同一组</b>令牌，缺一条整包拒绝；</li>
 *   <li>令牌名只能是 {@code --md-*}；</li>
 *   <li>值里不能有 {@code url()}、{@code @import}、{@code !important}、分号、花括号等 ——
 *       主题包只描述颜色与排版参数，不执行任何 CSS 规则；</li>
 *   <li>不能覆盖读者的独立设置：{@code --md-read-font} / {@code --md-read-size} 等保留令牌；</li>
 *   <li>单个令牌值不超过 240 字符，每档不超过 160 项，整个文件不超过 64KB。</li>
 * </ul>
 */

/** 结构令牌：同一套主题的明暗两档共用（颜色部分由各自的核心令牌派生）。 */
const STRUCTURE: Record<string, string> = {
  '--md-theme-line-height': '1.8',
  '--md-theme-heading-font': 'var(--md-read-font)',
  '--md-theme-heading-weight': '650',
  '--md-theme-heading-color': 'var(--md-text)',
  '--md-theme-heading-tracking': '-0.011em',
  '--md-theme-heading-space-top': '1.9em',
  '--md-theme-heading-space-bottom': '0.75em',
  '--md-theme-h1-size': '1.85em',
  '--md-theme-h1-weight': '700',
  '--md-theme-h1-tracking': '-0.02em',
  '--md-theme-h1-padding-bottom': '0.42em',
  '--md-theme-h1-rule':
    'linear-gradient(90deg, var(--md-ink-blue) 0%, var(--md-accent) 26%, color-mix(in srgb, var(--md-accent) 22%, transparent) 58%, transparent 100%)',
  '--md-theme-h1-rule-height': '3px',
  '--md-theme-h1-rule-radius': '3px',
  '--md-theme-h2-size': '1.42em',
  '--md-theme-h2-padding-left': '15px',
  '--md-theme-h2-padding-bottom': '0',
  '--md-theme-h2-border': '0',
  '--md-theme-h2-marker-width': '4px',
  '--md-theme-h2-marker-top': '0.14em',
  '--md-theme-h2-marker-bottom': '0.14em',
  '--md-theme-h2-marker-bg': 'linear-gradient(180deg, var(--md-ink-blue), var(--md-accent))',
  '--md-theme-h2-marker-radius': '3px',
  '--md-theme-h3-size': '1.18em',
  '--md-theme-h3-padding-left': '17px',
  '--md-theme-h3-marker-size': '8px',
  '--md-theme-h3-marker-top': '0.42em',
  '--md-theme-h3-marker-bg': 'linear-gradient(135deg, var(--md-ink-blue), var(--md-accent))',
  '--md-theme-h3-marker-radius': '2px',
  '--md-theme-h3-marker-transform': 'rotate(45deg)',
  '--md-theme-h4-size': '1.05em',
  '--md-theme-h4-color': 'var(--md-ink-blue)',
  '--md-theme-h5-color': 'var(--md-text-3)',
  '--md-theme-para-spacing': '1em',
  '--md-theme-strong-color': 'color-mix(in srgb, var(--md-ink-blue) 30%, var(--md-text))',
  '--md-theme-strong-weight': '650',
  '--md-theme-link-color': 'var(--md-primary)',
  '--md-theme-link-underline': 'color-mix(in srgb, var(--md-primary) 32%, transparent)',
  '--md-theme-link-hover': 'var(--md-primary-hover)',
  '--md-theme-hr-bg':
    'linear-gradient(90deg, transparent, var(--md-border-strong) 16%, color-mix(in srgb, var(--md-ink-blue) 38%, var(--md-border-strong)) 50%, var(--md-border-strong) 84%, transparent)',
  '--md-theme-list-marker': 'var(--md-accent)',
  '--md-theme-list-marker-deep': 'var(--md-text-3)',
  '--md-theme-list-ol-marker': 'var(--md-ink-blue)',
  '--md-theme-quote-bg':
    'linear-gradient(90deg, color-mix(in srgb, var(--md-ink-blue) 8%, transparent), color-mix(in srgb, var(--md-ink-blue) 2%, transparent))',
  '--md-theme-quote-bar-bg': 'linear-gradient(180deg, var(--md-ink-blue), var(--md-accent))',
  '--md-theme-quote-bar-width': '3px',
  '--md-theme-quote-radius': '0 var(--md-radius-sm) var(--md-radius-sm) 0',
  '--md-theme-quote-color': 'var(--md-text-2)',
  '--md-theme-inline-code-bg': 'color-mix(in srgb, var(--md-ink-teal) 10%, var(--md-surface-2))',
  '--md-theme-inline-code-color': 'var(--md-ink-teal)',
  '--md-theme-inline-code-radius': '6px',
  '--md-theme-code-radius': 'var(--md-radius)',
  '--md-theme-code-shadow': 'var(--md-shadow-sm)',
  '--md-theme-code-head-bg': 'color-mix(in srgb, var(--md-code-bg) 55%, var(--md-surface-2))',
  '--md-theme-code-scale': '0.87',
  '--md-theme-table-radius': 'var(--md-radius)',
  '--md-theme-table-bg': 'var(--md-surface)',
  '--md-theme-table-border': 'var(--md-border)',
  '--md-theme-table-head-bg':
    'linear-gradient(180deg, color-mix(in srgb, var(--md-ink-blue) 11%, var(--md-surface-2)), color-mix(in srgb, var(--md-ink-blue) 5%, var(--md-surface-2)))',
  '--md-theme-table-head-border': 'color-mix(in srgb, var(--md-ink-blue) 26%, var(--md-border))',
  '--md-theme-table-even-bg': 'color-mix(in srgb, var(--md-ink-blue) 3%, transparent)',
  '--md-theme-table-hover-bg': 'color-mix(in srgb, var(--md-ink-blue) 7%, transparent)',
  '--md-theme-table-first-hover-color': 'var(--md-ink-blue)',
  '--md-theme-table-scale': '0.935',
  '--md-theme-selection-bg': 'color-mix(in srgb, var(--md-primary) 22%, transparent)',
  '--md-theme-footnote-color': 'var(--md-text-2)',
  '--md-theme-footnote-scale': '0.87',
  '--md-theme-image-radius': 'var(--md-radius-sm)',
  '--md-theme-dt-color': 'var(--md-ink-blue)',
  '--md-theme-dd-border': 'var(--md-border)'
}

const LIGHT: Record<string, string> = {
  '--md-bg': '#f4f7fb',
  '--md-surface': '#ffffff',
  '--md-surface-2': '#f5f8fc',
  '--md-sidebar': '#fbfbfd',
  '--md-text': '#172033',
  '--md-text-2': '#43526a',
  '--md-text-3': '#7b879b',
  '--md-border': '#e5eaf2',
  '--md-border-strong': '#d5deec',
  '--md-primary': '#2563eb',
  '--md-primary-hover': '#1d4ed8',
  '--md-primary-soft': 'rgba(37,99,235,.08)',
  '--md-accent': '#14b8a6',
  '--md-ink-blue': '#1d4ed8',
  '--md-ink-teal': '#0f766e',
  '--md-ink-violet': '#6d28d9',
  '--md-ink-amber': '#a16207',
  '--md-ink-rose': '#be123c',
  '--md-ink-green': '#15803d',
  '--md-mark-bg': 'color-mix(in srgb, var(--md-ink-amber) 24%, transparent)',
  '--md-code-bg': '#f6f8fa',
  '--md-code-text': '#24292f',
  '--md-code-border': '#e3e6ee',
  '--md-tk-keyword': '#cf222e',
  '--md-tk-string': '#0a3069',
  '--md-tk-comment': '#6e7781',
  '--md-tk-number': '#0550ae',
  '--md-tk-title': '#8250df',
  '--md-tk-attr': '#953800',
  '--md-tk-builtin': '#0550ae',
  '--md-tk-type': '#0f766e',
  '--md-tk-meta': '#6e7781'
}

const DARK: Record<string, string> = {
  '--md-bg': '#0f1117',
  '--md-surface': '#161922',
  '--md-surface-2': '#1d2130',
  '--md-sidebar': '#14171f',
  '--md-text': '#e7e9f2',
  '--md-text-2': '#a8adc0',
  '--md-text-3': '#7c8199',
  '--md-border': '#262b3a',
  '--md-border-strong': '#333a4d',
  '--md-primary': '#6c8cff',
  '--md-primary-hover': '#8ba4ff',
  '--md-primary-soft': 'rgba(108,140,255,.18)',
  '--md-accent': '#2dd4bf',
  '--md-ink-blue': '#93b4ff',
  '--md-ink-teal': '#5eead4',
  '--md-ink-violet': '#c4b5fd',
  '--md-ink-amber': '#fcd34d',
  '--md-ink-rose': '#fda4af',
  '--md-ink-green': '#86efac',
  '--md-mark-bg': 'color-mix(in srgb, var(--md-ink-amber) 34%, transparent)',
  '--md-code-bg': '#1b1f2b',
  '--md-code-text': '#e7e9f2',
  '--md-code-border': '#2b3143',
  '--md-tk-keyword': '#ff7b72',
  '--md-tk-string': '#a5d6ff',
  '--md-tk-comment': '#8b949e',
  '--md-tk-number': '#79c0ff',
  '--md-tk-title': '#d2a8ff',
  '--md-tk-attr': '#ffa657',
  '--md-tk-builtin': '#79c0ff',
  '--md-tk-type': '#5eead4',
  '--md-tk-meta': '#8b949e'
}

export const THEME_TEMPLATE = {
  id: 'my-theme',
  name: '我的主题',
  description: '一套从 MiniDocs 默认主题改出来的自定义配色。',
  author: '你的名字',
  version: '1.0.0',
  license: 'MIT',
  light: { ...LIGHT, ...STRUCTURE },
  dark: { ...DARK, ...STRUCTURE }
}

/** 触发浏览器下载；文件名里带 svg 风格的连字符，避免不同系统对中文名的编码差异。 */
export function downloadThemeTemplate() {
  const blob = new Blob([JSON.stringify(THEME_TEMPLATE, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'minidocs-theme-template.json'
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
