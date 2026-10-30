import { reactive, ref } from 'vue'
import type { DocNode } from '@/shared/api/types'

/**
 * 目录树的展开状态：**模块级共享**，不挂在任何组件实例上。
 *
 * <p><b>为什么必须提出来。</b>{@code DocTree} 是递归组件，每个实例只持有自己直接渲染的那一节，
 * 「全部展开 / 全部折叠」这颗按钮长在左栏头部、根本够不着里面那些实例。
 * 早先状态是每个实例自己的 {@code reactive}，于是要么把递归实例全挂上 ref 逐个调，
 * 要么在主组件里另抄一份棵树的状态再往下灌 —— 两条路都会长出第二份真相，开合一次就对不上。</p>
 *
 * <p>路径在同一个知识库里天然唯一，所以一整棵树的开合状态可以就摊平成一个
 * {@code Record<path, boolean>}，谁读谁写都是同一份。</p>
 *
 * <p><b>不随树重置</b>：路由切文档时 {@code view.tree} 是新对象，若跟着重置，
 * 读者点开下一篇目录就全塌了。留着旧键也无害 —— 别的库的路径撞不上同一个键。</p>
 */
const expanded = reactive<Record<string, boolean>>({})

/**
 * 开合次数的计数器。纯展示用的派生判断（「是不是全展开了」）要读到它才可靠：
 * reactive 对「某个键从没被读过」的情况不一定触发重算，而这个计数器每次开合都自增，
 * 谁读谁就跟着更新，不必去数键。
 */
const revision = ref(0)

export function isTreeExpanded(path: string): boolean {
  return !!expanded[path]
}

export function toggleTreeNode(path: string): void {
  expanded[path] = !expanded[path]
  revision.value += 1
}

export function expandTreeNode(path: string): void {
  if (expanded[path]) return
  expanded[path] = true
  revision.value += 1
}

/**
 * 整棵树一律展开或一律折叠。
 *
 * <p>递归 writes 而不是「每层实例自己跑一遍」：那颗按钮要的是**一次点击整棵树到位**，
 * 靠递归组件各自的 watch 逐层生效的话，按钮点完到叶子层之间会有一段「开了一半」的中间态，
 * 读者看着就像按钮时灵时不灵。</p>
 */
export function setTreeExpanded(nodes: DocNode[] | undefined, open: boolean): void {
  for (const node of nodes || []) {
    if (node.type !== 'dir') continue
    expanded[node.path] = open
    setTreeExpanded(node.children, open)
  }
  revision.value += 1
}

/**
 * 是不是每一层目录都展开了 —— 按钮上该写「全部折叠」还是「全部展开」。
 *
 * <p>一棵目录都没有时返回 {@code false}（按钮写「全部展开」）：这时候点了也没用，
 * 但至少不会出现「写着全部折叠、点一下却什么也没发生」的谎报。</p>
 */
export function isTreeAllExpanded(nodes: DocNode[] | undefined): boolean {
  // 读一下计数器：让调用方（模板 / computed）把这次调用挂到开合事件上
  void revision.value
  let anyDir = false
  const walk = (list?: DocNode[]): boolean => {
    for (const node of list || []) {
      if (node.type !== 'dir') continue
      anyDir = true
      if (!expanded[node.path]) return false
      if (!walk(node.children)) return false
    }
    return true
  }
  return walk(nodes) && anyDir
}
