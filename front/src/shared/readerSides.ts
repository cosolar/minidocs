import { reactive } from 'vue'

/**
 * 阅读页两侧栏的显示状态（纯浏览器端）。
 *
 * <p>左侧目录与右侧大纲默认都显示；读者想专心读正文时可以各自关掉，把版面还给正文。
 * 状态存 localStorage，门户阅读页与分享页共用 —— 同一台浏览器上读哪篇都按这个来。</p>
 *
 * <p>只在桌面布局（≥901 的左栏、≥1280 的右栏）生效：窄屏下两栏本来就收进抽屉，
 * 不占版面，关掉它们只会让「目录 / 大纲」入口消失，反而少了导航。</p>
 *
 * <p>用 {@code reactive} 而不是只写 DOM：面板与正文栏是两个组件，
 * 通过这份共享状态联动，读者一按开关就能看到版面变化。</p>
 */

/** 'aside' = 左侧目录，'outline' = 右侧大纲；true 表示显示 */
export type ReaderSide = 'aside' | 'outline'

const STORAGE_KEY = 'minidocs.reader-sides'

interface ReaderSideState {
  aside: boolean
  outline: boolean
}

function readReaderSides(): ReaderSideState {
  try {
    const raw = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}') as Partial<ReaderSideState>
    return {
      aside: typeof raw.aside === 'boolean' ? raw.aside : true,
      outline: typeof raw.outline === 'boolean' ? raw.outline : true
    }
  } catch {
    // 被手改坏的存储值就当没设过，退回默认全显示
    return { aside: true, outline: true }
  }
}

/** 面板与其他组件共用的单例状态 */
export const readerSides = reactive<ReaderSideState>(readReaderSides())

export function setReaderSide(side: ReaderSide, visible: boolean) {
  readerSides[side] = visible
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ aside: readerSides.aside, outline: readerSides.outline })
    )
  } catch {
    /* 存不下只影响跨刷新，本次会话内仍然生效 */
  }
}
