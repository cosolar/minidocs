package cn.minims.minidocs.permission;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.share.entity.Share;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantMember;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 权限裁决的唯一入口（规范 §2.6）。
 *
 * <p>除本类之外，业务代码不得再出现角色字面量或 {@code owner_id} 比较。两条轴在此分开实现：
 * 读走 {@code visibility}（§2.2），写走 {@code maintain_scope}（§2.3），互不推导。</p>
 *
 * <p>{@code can*} 系列是纯判定，无副作用；{@code require*} 系列才写审计（I7）并按 §2.5 抛
 * 「无读权限 404 / 有读无写 403」。</p>
 */
public interface AccessService {

    /** 读权限：{@link KbAction#KB_VIEW} 与 {@link KbAction#DOC_READ} 共用同一判定。 */
    boolean canRead(KnowledgeBase kb, LoginUser user);

    /** 任意动作判定（读动作走 canRead，写与治理动作走 §2.3/§2.4）。 */
    boolean can(KnowledgeBase kb, LoginUser user, KbAction action);

    /**
     * 详情页一次性给出「这个人能做什么」，前端据此禁用按钮，不自行复制判定（规范 §4.2）。
     *
     * <p>逐轴问一次而不是逐动作问：{@code MAINTAIN} 与 {@code GOVERN} 各自内部条件相同
     * （§2.3「档位决定写权」、§2.4「组织管理员与创建者决定治理权」），12 个动作各问一次等于
     * 把同一份组织事实重查 12 遍。因此新增动作若与同轴动作条件不同，必须在此显式补一轴。</p>
     */
    default List<KbAction> permissionsOf(KnowledgeBase kb, LoginUser user) {
        if (kb == null) {
            return List.of();
        }
        List<KbAction> granted = new ArrayList<>();
        if (can(kb, user, KbAction.KB_VIEW)) {
            granted.add(KbAction.KB_VIEW);
            granted.add(KbAction.DOC_READ);
            if (can(kb, user, KbAction.DOC_WRITE)) {
                // 写轴全集：同一判定条件，一次裁决全部授予（§2.3）
                granted.addAll(List.of(KbAction.KB_EDIT_META, KbAction.DOC_WRITE, KbAction.DOC_RENAME,
                        KbAction.DOC_MOVE, KbAction.DOC_DELETE, KbAction.DOC_UPLOAD_ASSET,
                        KbAction.DOC_IMPORT, KbAction.SHARE_CREATE));
            }
            if (can(kb, user, KbAction.KB_DELETE)) {
                // 治理轴全集：不受 maintain_scope 影响（§2.4）
                granted.addAll(List.of(KbAction.KB_DELETE, KbAction.KB_SET_VISIBILITY,
                        KbAction.KB_MEMBER_MANAGE));
            }
        }
        return List.copyOf(granted);
    }


    /** 组织级动作（KB_CREATE 与 TENANT_*），与库无关。 */
    boolean canInTenant(Tenant tenant, LoginUser user, KbAction action);

    /**
     * 某人在该组织里的档位事实（查库一次）。
     *
     * <p>单独开这一个入口，是为了让 {@code /api/me} 与组织详情能给出与 {@link #canInTenant}
     * 完全同一份口径的动作集；除此之外不得在业务代码里读角色。</p>
     */
    OrgRole roleOf(Tenant tenant, LoginUser user);

    /**
     * 组织档位（{@link OrgRole}）→ 组织级动作全集。纯函数，不查库。
     *
     * <p>与 {@link #permissionsOf(KnowledgeBase, LoginUser)} 同理：同一档位的动作一次给全。
     * 因为它是纯函数，切换器的每一个组织条目都带得上这份动作集（{@code /api/me} 一次装载
     * 已经把人家的成员行和组织行都查出来了，这里零额外查询），前端因此不需要按角色字面量
     * 自己推断「ADMIN 能不能改名字」——那条推断一旦与这里的档位表漂移，就会出现「按钮能点、
     * 接口 403」或反过来「接口允许、按钮没给」。</p>
     */
    default List<KbAction> permissionsOfTenant(OrgRole role) {
        if (role == null || !role.granted()) {
            return List.of();
        }
        List<KbAction> granted = new ArrayList<>(List.of(KbAction.TENANT_VIEW, KbAction.KB_CREATE));
        if (role.orgAdmin()) {
            granted.addAll(List.of(KbAction.TENANT_MEMBER_MANAGE, KbAction.TENANT_JOIN_REVIEW,
                    KbAction.AUDIT_READ));
        }
        if (role.owner()) {
            granted.addAll(List.of(KbAction.TENANT_RENAME, KbAction.TENANT_DELETE, KbAction.TENANT_TRANSFER,
                    KbAction.TENANT_APPOINT_ADMIN));
        }
        return List.copyOf(granted);
    }

    /**
     * 某人在某组织里的档位事实。
     *
     * <p>{@code active=false}（组织不存在或已停用）时四个身份位一律清零：停用组织里残留的
     * OWNER 成员行不该继续产生任何权利，与 {@code orgFacts} 同一条口径。</p>
     */
    record OrgRole(boolean member, boolean orgAdmin, boolean owner) {

        /** 非成员 / 组织已停用。 */
        public static OrgRole none() {
            return new OrgRole(false, false, false);
        }

        /** 由成员行的角色给出；组织不在活跃状态时传 {@link #none()}。 */
        public static OrgRole of(String memberRole, boolean tenantActive) {
            if (!tenantActive || memberRole == null) {
                return none();
            }
            return switch (memberRole.toUpperCase(Locale.ROOT)) {
                case TenantMember.ROLE_OWNER -> new OrgRole(true, true, true);
                case TenantMember.ROLE_ADMIN -> new OrgRole(true, true, false);
                default -> new OrgRole(true, false, false);
            };
        }

        /** 有没有任何组织级权利：成员且组织活跃。 */
        public boolean granted() {
            return member;
        }
    }

    /**
     * 载入库并完成裁决。
     *
     * @throws cn.minims.minidocs.common.exception.BizException 库不存在或无读权限 → 404；
     *                                                          有读无写/无治理权 → 403
     */
    KnowledgeBase requireKb(Long kbId, KbAction action, LoginUser user);

    /** 分享动作（SHARE_REVOKE / SHARE_VIEW_STATS）：看分享创建者，不看库创建者。 */
    Share requireShare(Share share, KbAction action, LoginUser user);

    /**
     * 这条分享能不能由我来改 / 撤销：创建者本人、所属组织的管理员、超管（§2.4）。
     *
     * <p>与 {@link #requireShare} 同一份判定，只是不抛错、不留痕：分享列表与详情要把结论随
     * {@code ShareVO} 发出去，前端才知道哪一行的「撤销」该点亮。让前端自己按 owner 猜，
     * 就会出现「按钮亮着、点下去 403」。</p>
     */
    boolean canGovernShare(Share share, KnowledgeBase kb, LoginUser user);

    /** 组织级动作裁决：非成员 404，成员权限不足 403（§2.5）。 */
    Tenant requireTenant(Tenant tenant, KbAction action, LoginUser user);

    /**
     * 组织成员门：非成员一律 404，与「组织不存在」不可区分（§2.5）。
     *
     * <p>与 {@link #requireTenant} 的差别只有两点：不判具体动作、不留超管痕迹。成员门要跑在
     * 每一个 {@code /api/console/**} 请求上，在那里留痕等于管理员每翻一页就多一条审计（F9 的口径是
     * 「管理员的普通读不留痕」）；动作级留痕仍由 {@code requireTenant} 负责。</p>
     */
    Tenant requireMember(Tenant tenant, LoginUser user);

    /**
     * 把可见集条件加到查询上，是列表 / 搜索 / 统计的唯一读出口。
     *
     * <p>本方法不留痕：逐字符联想会把审计刷满，超管读取内容的动作在 {@link #requireKb} 处记录。</p>
     *
     * @param tenantId 组织上下文；为 null 表示跨组织（如门户「我的全部库」）
     */
    void applyVisibleScope(LambdaQueryWrapper<KnowledgeBase> wrapper, LoginUser user, Long tenantId);

    /**
     * 「我参与的那部分库」条件：创建者，或本组织内的名单成员。
     *
     * <p>这是列表筛选项而非放行入口——它必须与 {@link #applyVisibleScope} 取交集使用，
     * 单独使用不产生任何权限含义。</p>
     */
    void applyParticipatingScope(LambdaQueryWrapper<KnowledgeBase> wrapper, LoginUser user);

    /**
     * 只加「所属组织未停用」这一条，不附加任何可见性条件。
     *
     * <p>门户的可见集不再由 {@code visibility} 决定（分享等同于发布），但停用组织的内容仍要
     * 一并下线 —— 这一条与 {@link #canRead} 同口径，是门户列表与阅读页唯一共用的组织闸门。</p>
     */
    void applyTenantActiveScope(LambdaQueryWrapper<KnowledgeBase> wrapper);
}
