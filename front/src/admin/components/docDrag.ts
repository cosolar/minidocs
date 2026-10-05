import { ref } from 'vue'

/**
 * 正在被拖动的节点路径。
 *
 * <p>必须是模块级单例：树是递归组件，落点目录要看到「谁在被我拖着」才能判定自己是不是
 * 合法落点。写在 {@code <script setup>} 里的 ref 是每个实例各一份，目录行读到的永远是空串，
 * 拖拽会被自己的 canDrop 直接拦掉。</p>
 */
export const draggingPath = ref('')