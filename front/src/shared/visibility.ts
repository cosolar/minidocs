import type { KbVO } from './api/types'

/**
 * 可见范围档位的展示口径（读轴：平台内谁能读）。
 *
 * <p>三档是读轴（谁能看），与维护档位（谁能改）正交，两者不可混在同一个下拉里（规范 §2.2/§2.3）。
 * 个人组织只有一名成员，所以它的 {@code org} 库实际等同私有 —— 这也是 v2 把建库默认档定成
 * {@code org} 而不泄露的原因。</p>
 *
 * <p><b>刻意不用「公开 / 私有」这个词</b>：门户那边的「公开 / 私有」说的是发布后要不要口令，
 * 两处同名会被当成同一件事，而它们其实正交（见 {@link publishStatusLabel}）。这里改用
 * 「谁」来命名，读者一眼能看出这是在说平台内的人。</p>
 */
const LABELS: Record<KbVO['visibility'], string> = {
  public: '所有登录用户',
  org: '本组织成员',
  private: '仅维护名单'
}

const HINTS: Record<KbVO['visibility'], string> = {
  public: '平台内所有登录用户都可以读这个库；不影响它是否出现在门户上',
  org: '本组织成员可以读，组织外的人看不到',
  private: '仅创建者、组织管理员与维护名单中的成员可见'
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

/* ---------------------------------------------------------------- 发布态（对外那一层） */

/**
 * 发布态的展示口径：这个库在门户上是什么状态。
 *
 * <p>与上面的可见范围<b>正交</b>，所以文案里不出现「谁」而出现「对外」：
 * {@code unpublished} 没有任何整库分享链接，门户上查无此库；
 * {@code public} 已发布且未加密，陌生人无需登录即可读，界面上一律简称「共享」；
 * {@code private} 已发布但设了访问密码，简称「加密」。</p>
 *
 * <p><b>门户侧不显示「未发布」这一档</b>——那里只列已发布的库，看得见就等于已发布。
 * 这一档是给控制台用的：不然建完库的人无从判断「我的库怎么在门户上找不到」，
 * 只能靠「可见范围选了公开」去猜，而那个设置对门户毫无影响。</p>
 */
type PublishStatus = NonNullable<KbVO['shareStatus']>

const PUBLISH_LABELS: Record<PublishStatus, string> = {
  unpublished: '未发布',
  public: '已发布 · 共享',
  private: '已发布 · 加密'
}

const PUBLISH_HINTS: Record<PublishStatus, string> = {
  unpublished: '还没有整库分享链接，门户上不会出现这个库。需要对外分享请到「分享」里创建链接',
  public: '门户上可以找到，任何人拿到链接即可阅读全部内容（包括未单独分享的文档），无需登录',
  private: '门户上可以找到，读者需要输入访问密码才能阅读'
}

/**
 * 徽标配色：未发布=灰（与「私密的库」同色，但语义不同，见类名注释）、
 * 共享=绿、加密=蓝。沿用可见范围那三枚色，不新增变量。
 */
const PUBLISH_CHIP_CLASS: Record<PublishStatus, string> = {
  unpublished: 'md-ad-chip--private',
  public: 'md-ad-chip--public',
  private: 'md-ad-chip--org'
}

/** 缺省按「未发布」处理：没给这一格时，最诚实的说法就是它还没发布。 */
export function publishStatusLabel(value: KbVO['shareStatus'] | string | undefined): string {
  return PUBLISH_LABELS[(value || 'unpublished') as PublishStatus] || PUBLISH_LABELS.unpublished
}

export function publishStatusHint(value: KbVO['shareStatus'] | string | undefined): string {
  return PUBLISH_HINTS[(value || 'unpublished') as PublishStatus] || ''
}

export function publishStatusChipClass(value: KbVO['shareStatus'] | string | undefined): string {
  return PUBLISH_CHIP_CLASS[(value || 'unpublished') as PublishStatus] || PUBLISH_CHIP_CLASS.unpublished
}
