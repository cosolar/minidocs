package cn.minims.minidocs.permission;

/**
 * 权限裁决的动作全集（规范 §2.1）。
 *
 * <p>动作自带归类，是为了让「读走 visibility 轴、写走 maintain_scope 轴」这条正交关系
 * 只表达一次：新增动作时必须显式归类，漏归类会在编译期就暴露，而不是变成一条静默放行。</p>
 */
public enum KbAction {

    // ---------------------------------------------------------------- 读轴（visibility）
    KB_VIEW(Axis.READ),
    DOC_READ(Axis.READ),

    // ---------------------------------------------------------------- 写轴（maintain_scope）
    KB_EDIT_META(Axis.MAINTAIN),
    DOC_WRITE(Axis.MAINTAIN),
    DOC_RENAME(Axis.MAINTAIN),
    DOC_MOVE(Axis.MAINTAIN),
    DOC_DELETE(Axis.MAINTAIN),
    DOC_UPLOAD_ASSET(Axis.MAINTAIN),
    DOC_IMPORT(Axis.MAINTAIN),
    /** 分享权限等同写权限：能改内容的人就应能对外给出只读链接（规范 §2.4） */
    SHARE_CREATE(Axis.MAINTAIN),

    // ---------------------------------------------------------------- 治理轴（组织管理员 / 创建者）
    KB_CREATE(Axis.TENANT_MEMBER),
    KB_DELETE(Axis.GOVERN),
    KB_SET_VISIBILITY(Axis.GOVERN),
    KB_MEMBER_MANAGE(Axis.GOVERN),
    /** 撤销权看「该分享的创建者」，不看库归属，故单列（AccessService.requireShare） */
    SHARE_REVOKE(Axis.SHARE_OWNER),
    SHARE_UPDATE(Axis.SHARE_OWNER),
    SHARE_VIEW_STATS(Axis.SHARE_OWNER),

    // ---------------------------------------------------------------- 组织轴
    /**
     * 组织详情（{@code GET /api/console/{org}}）。
     *
     * <p>与 {@code KB_CREATE} 同轴（活跃成员即可），是因为非成员根本进不到组织上下文：
     * 那条路径由 {@code requireTenant} 直接给 404（§2.5），公开资料只经发现列表的专用查询出口。
     * 单列一个动作而不复用 {@code TENANT_MEMBER}，是为了让「读详情」与「建库」在审计与接口清单里可对得上号。</p>
     */
    TENANT_VIEW(Axis.TENANT_MEMBER),
    TENANT_RENAME(Axis.TENANT_OWNER),
    TENANT_DELETE(Axis.TENANT_OWNER),
    /** 转让 OWNER：只有现任 OWNER 能做，且与「删除组织」同档 —— ADMIN 不得自抬 */
    TENANT_TRANSFER(Axis.TENANT_OWNER),
    TENANT_MEMBER_MANAGE(Axis.TENANT_ADMIN),
    /**
     * 任免管理员：把某人升到 ADMIN、把 ADMIN 降回 MEMBER、或移除一个 ADMIN。
     *
     * <p>单列而不并入 {@code TENANT_MEMBER_MANAGE}，是因为 ADMIN 也能管成员，但他管的是
     * 普通成员 —— 一条「ADMIN 能把同伴变成管理员」的路径等于任何管理员都能自扩权限。
     * 前端拿这个动作决定按钮，就不再需要抄一遍「只有 OWNER 能任免」这条规则。</p>
     */
    TENANT_APPOINT_ADMIN(Axis.TENANT_OWNER),
    TENANT_JOIN_REVIEW(Axis.TENANT_ADMIN),
    AUDIT_READ(Axis.TENANT_ADMIN),

    /**
     * 停用 / 恢复组织（平台侧）。
     *
     * <p>单列一条 {@link Axis#PLATFORM} 而不是挂在 {@code TENANT_OWNER} 上：这条动作的判定入口是
     * {@code AdminInterceptor}（平台角色），不是任何组织的成员身份。挂在组织轴上会让「组织 OWNER
     * 停用组织自己」看起来合法，而那条路径没有任何治理意义 —— 它连删除都做不过的组织清库检查都能绕过。</p>
     */
    TENANT_SUSPEND(Axis.PLATFORM);

    /**
     * 动作归属的判定轴。
     *
     * <ul>
     *   <li>{@link #READ} —— 由 {@code visibility} 决定（规范 §2.2）；</li>
     *   <li>{@link #MAINTAIN} —— 由 {@code maintain_scope} 决定（§2.3），且必须先有读权限；</li>
     *   <li>{@link #GOVERN} —— 超管 / 组织 OWNER|ADMIN / 创建者，<b>不受 maintain_scope 影响</b>：
     *       名单里的 EDITOR 能改内容，不能删库；</li>
     *   <li>{@link #SHARE_OWNER} / 组织轴 —— 见 §2.4；</li>
     *   <li>{@link #PLATFORM} —— 只有平台角色，任何组织身份都不产生这条权利。</li>
     * </ul>
     */
    public enum Axis {
        READ,
        MAINTAIN,
        GOVERN,
        SHARE_OWNER,
        TENANT_MEMBER,
        TENANT_ADMIN,
        TENANT_OWNER,
        PLATFORM
    }

    private final Axis axis;

    KbAction(Axis axis) {
        this.axis = axis;
    }

    public Axis axis() {
        return axis;
    }

    public boolean isRead() {
        return axis == Axis.READ;
    }
}
