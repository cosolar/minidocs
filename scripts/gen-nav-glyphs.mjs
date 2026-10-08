import { writeFileSync } from 'node:fs'
const SET = 'tabler'
const API = 'https://api.iconify.design'
const OUT = 'front/src/shared/navGlyphs.generated.ts'
const Q = {
  文档: ['book','books','notebook','file','files','folder','folder-open','file-text','clipboard','note'],
  分类: ['tag','tags','label','folder','stack','layers','list','checklist','index','category'],
  时间: ['clock','calendar','history','timeline','hourglass','calendar-event','sun','moon'],
  媒体: ['photo','camera','video','music','artboard','palette','brush','film','image'],
  链接: ['link','external-link','world','globe','share','send','address-book','affiliate'],
  组织: ['user','users','building','team','user-circle','id','briefcase','school'],
  数据: ['chart','analytics','table','report','database','cpu','activity','gauge'],
  动作: ['search','star','heart','bookmark','flag','target','bulb','compass','map','route'],
  形态: ['home','grid','layout','apps','menu','dots','square','circle','hexagon','shape'],
  工具: ['settings','tool','wrench','code','terminal','box','package','key','shield','function']
}
const SKIP = /-(off|filled|disabled)$|^.*-\d+$/
const found = new Map()
for (const [g, words] of Object.entries(Q)) {
  for (const w of words) {
    try {
      const r = await fetch(`${API}/search?query=${encodeURIComponent(w)}&prefix=${SET}&limit=10`)
      if (!r.ok) continue
      const j = await r.json()
      for (const n of (j.icons || [])) {
        const name = n.replace(SET + ':', '')
        if (SKIP.test(name) || found.has(name)) continue
        found.set(name, g)
      }
    } catch (e) { console.warn('search fail ' + w) }
  }
  console.log(g + ': ' + found.size)
}
const cap = 240
const names = [...found.keys()].slice(0, cap)
const glyphs = {}
for (let i = 0; i < names.length; i += 60) {
  const batch = names.slice(i, i + 60)
  try {
    const r = await fetch(`${API}/${SET}.json?icons=${batch.join(',')}`)
    if (!r.ok) { console.warn('batch ' + i + ' -> ' + r.status); continue }
    const j = await r.json()
    for (const [n, d] of Object.entries(j.icons || {})) {
      glyphs[n] = { body: d.body, w: d.width || j.width || 24, h: d.height || j.height || 24 }
    }
    console.log('fetched ' + Object.keys(glyphs).length)
  } catch (e) { console.warn('batch fail ' + i) }
}
const sorted = Object.keys(glyphs).sort()
const meta = Object.fromEntries(sorted.map((n) => [n, found.get(n)]))
const out = [
  '/* eslint-disable */',
  '//',
  '// 由 scripts/gen-nav-glyphs.mjs 生成，请勿手工编辑。',
  '// 图标集：' + SET + '（MIT）。增删图标请改脚本里的关键词表后重新生成。',
  '//',
  '// 只存路径与画布尺寸：渲染靠 currentColor 取色，因此自动适配明暗主题。',
  '',
  'export interface Glyph { body: string; w: number; h: number }',
  '',
  '/** 语义分组，仅用于选择器里的分组标题与搜索加权 */',
  'export const GLYPH_GROUP: Record<string, string> = ' + JSON.stringify(meta, null, 0),
  '',
  '/** name -> 路径数据 */',
  'export const GLYPHS: Record<string, Glyph> = {'
].concat(sorted.map((n) => '  ' + JSON.stringify(n) + ': { body: ' + JSON.stringify(glyphs[n].body) + ", w: " + glyphs[n].w + ', h: ' + glyphs[n].h + ' },')).concat(['}', ''])
writeFileSync(OUT, out.join('\n'), 'utf8')
console.log('WROTE ' + OUT + '  total=' + sorted.length)
