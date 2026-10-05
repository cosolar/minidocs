import { ref } from 'vue'

/**
 * 全局搜索面板的显隐。
 *
 * <p>顶栏的搜索触发器与面板本身是兄弟组件，用模块级 ref 共享，
 * 比 prop/emit 逐层透传更省事，且天然单例。</p>
 */
const visible = ref(false)
const keyword = ref('')

export function useSearchPanel() {
  return {
    visible,
    keyword,
    open() {
      visible.value = true
    },
    close() {
      visible.value = false
    },
    toggle() {
      visible.value = !visible.value
    }
  }
}
