import type { KbVO } from './api/types'

/**
 * 可见性档位的展示口径。
 *
 * <p>三档是读轴（谁能看），与维护档位（谁能改）正交，两者不可混在同一个下拉里（规范 §2.2/§2.3）。
 * 个人组织只有一名成员，所以它的 {@code org} 库实际等同私有 —— 这也是 v2 把建库默认档定成
 * {@code org} 而不泄露的原因。</p>
 */
const LABELS: Record<KbVO['visibility'], string> = {
  public: '公开',
  org: '本组织',
  private: '私有'
}

const HINTS: Record<KbVO['visibility'], string> = {
  public: '门户对所有人可见',
  org: '仅组织成员可见',
  private: '仅创建者、组织管理员与维护名单可见'
}

export function visibilityLabel(value: KbVO['visibility'] | string): string {
  return LABELS[value as KbVO['visibility']] || '未知'
}

export function visibilityHint(value: KbVO['visibility'] | string | undefined): string {
  return HINTS[value as KbVO['visibility']] || ''
}

/**
 * 可见性小章 / 徽标的配色类名，三档各一色。
 *
 * <p>org 与 private 共用灰色会被读成「这两档是一回事」，而 org 恰恰是团队库最常用的那一档
 * （规范 §11.11）。类名定义在 admin.css，深色档由 {@code --md-vis-*} 变量提亮。</p>
 */
export function visibilityChipClass(value: KbVO['visibility'] | string | undefined): string {
  if (!value) return ''
  return value === 'public' ? 'md-ad-chip--public' : value === 'private' ? 'md-ad-chip--private' : 'md-ad-chip--org'
}

/** 脏值按最严处理：只有 public 才是公开，其余一律不是（与后端判定的口径一致）。 */
export function isPublicKb(kb: Pick<KbVO, 'visibility'>): boolean {
  return kb.visibility === 'public'
}

/**
 * 维护档位（写轴，规范 §2.3）的展示口径。
 *
 * <p>与可见性放在一起，是为了让「两条轴各有一个下拉」这件事在一处看得清；档位的实际效果由
 * 后端按 {@code AccessService} 裁决，这里只是文案。名单档（{@code members}）的含义依赖维护名单
 * 的内容，所以文案要点明「名单里 EDITOR 可写」，别让人以为「成员」指的是组织成员。</p>
 */
const SCOPE_LABELS: Record<NonNullable<KbVO['maintainScope']>, string> = {
  owner_only: '仅我自己',
  members: '维护名单',
  org_all: '全组织可写'
}

const SCOPE_HINTS: Record<NonNullable<KbVO['maintainScope']>, string> = {
  owner_only: '只有创建者和组织管理员能改内容，名单上的授权暂时不生效',
  members: '创建者、组织管理员与维护名单中的 EDITOR 能改内容；VIEWER 只能读',
  org_all: '组织内任何成员都能改内容（组织外仍按可见性只读）'
}

export function maintainScopeLabel(value: KbVO['maintainScope'] | string | undefined): string {
  return SCOPE_LABELS[(value || 'owner_only') as NonNullable<KbVO['maintainScope']>] || '仅我自己'
}

export function maintainScopeHint(value: KbVO['maintainScope'] | string | undefined): string {
  return SCOPE_HINTS[(value || 'owner_only') as NonNullable<KbVO['maintainScope']>] || ''
}
