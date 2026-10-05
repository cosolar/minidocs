import type { KbActionName } from './types'

/**
 * 动作向量判定：后端下发什么就用什么，前端不重抄档位规则（规范 §2.6）。
 *
 * <p>按钮显隐与接口裁决读同一份 {@code KbAction} 清单，就不会出现「按钮给了 ADMIN 却 403」
 * 或反过来「接口允许但入口没给」。这里的 {@code myPermissions} 载体可以是库详情、组织详情，
 * 也可以是切换器条目。</p>
 *
 * <p>空向量的含义是「本次没算」（门户的几条链路故意不给），此时按放行处理：漏给值是后端的
 * bug，代价应当是多显示一个按钮并被后端 403 挡下，而不是把功能整片藏起来。</p>
 */
export function can(actions: KbActionName[] | null | undefined, action: KbActionName): boolean {
  if (!actions || !actions.length) return true
  return actions.includes(action)
}
