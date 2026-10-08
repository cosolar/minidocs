package cn.minims.minidocs.share.support;

import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.share.entity.Share;
import cn.minims.minidocs.share.mapper.ShareMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「发布」查询：分享等同于发布，只有整库分享（{@code scope=kb}）且仍有效的库才对外公开。
 *
 * <p>这里刻意把「什么算已发布」收敛成一份口径（{@link #publishedExists} 的 SQL 与
 * {@link #find} / {@link #findOf} 的 Lambda 必须一致）：门户列表、门户统计、阅读页放行、
 * 老链接消歧四处都要问同一个问题，各写一遍迟早会出现「列表里有、点进去 404」。</p>
 *
 * <p>「有效」= 未撤销、未失效、未过期。过期链接应当把库一并从门户撤下 —— 链接都打不开了，
 * 还留着一张点进去要口令的卡片只是误导。单篇分享（{@code scope=doc}）不参与发布：
 * 它只提供一篇文档的独立链接，把整库推上门户会连带暴露没分享过的内容。</p>
 */
@Component
@RequiredArgsConstructor
public class SharePublicationSupport {

    /**
     * 发布态：分享未加密，门户上无需口令即可阅读。
     *
     * <p>与 {@code knowledge_base.visibility} 里的 {@code public} 是两回事：那个是平台内的读权限
     * （所有登录用户可读），这个是对外发布后的访问方式（陌生人要不要口令）。两者正交，
     * 名字撞车是历史遗留，所以控制台侧一律配「已发布 · 共享 / 已发布 · 加密」这样的完整文案，
     * 门户侧简称「共享 / 加密」，都不单说「公开」。</p>
     */
    public static final String ACCESS_PUBLIC = "public";
    /** 发布态：分享已加密，门户上需输入访问密码。 */
    public static final String ACCESS_PRIVATE = "private";
    /**
     * 发布态：未发布 —— 没有有效的整库分享，门户上不出现。
     *
     * <p>只可能出现在控制台链路：门户的查询（{@link #publishedExists}）天然只出已发布的库，
     * 所以门户侧永远拿不到这一档。它存在的意义是让建库的人<b>看得见</b>「我这个库还没对外」，
     * 而不是只能靠「门户上没搜到」倒推。</p>
     */
    public static final String PUBLISH_UNPUBLISHED = "unpublished";

    private final ShareMapper shareMapper;

    /**
     * 「已发布」的查询条件（配合 {@code wrapper.apply(sql, TimeUtil.now())} 使用）。
     *
     * <p>{@code {0}} 是过期判定的当前时刻占位符；{@code knowledge_base.id} 是列表查询里的主表别名，
     * 与 {@code AccessService} 里那些 EXISTS 片段同一写法。</p>
     *
     * @param access {@link #ACCESS_PUBLIC} / {@link #ACCESS_PRIVATE} 再按是否加密筛；null 不筛
     */
    public static String publishedExists(String access) {
        String base = "EXISTS (SELECT 1 FROM shares s WHERE s.kb_id = knowledge_base.id"
                + " AND s.scope = 'kb' AND s.revoked = 0 AND s.invalid = 0"
                + " AND (s.expires_at IS NULL OR s.expires_at > {0})";
        if (ACCESS_PUBLIC.equals(access)) {
            return base + " AND s.password_hash IS NULL)";
        }
        if (ACCESS_PRIVATE.equals(access)) {
            return base + " AND s.password_hash IS NOT NULL)";
        }
        return base + ")";
    }

    /**
     * 门户曝光范围的 SQL 片段，供门户列表把「已发布」收窄到「对我可见」。
     *
     * <p>与 {@link #publishedExists} 分开而不是合并进去：统计卡与链接消歧问的是「发布了吗」，
     * 列表问的是「发布了且对我可见吗」。两个问题混进一个条件后，加发布态的判断会被顺手
     * 绑上当前用户，于是「库数统计」这种与访客无关的口径也会开始漏数。</p>
     *
     * <p>另起一个别名 {@code ps} 而不是复用外层的 {@code s}：两次 {@code apply} 叠出来的
     * 括号层级本来就已经难读，共用别名更让人怀疑条件被合并了。</p>
     *
     * <p><b>userId 必须走 {@code {0}} 占位符，不能拼进 SQL</b>：拼接时 null 会变成字面量
     * {@code owner_id = null}，那是个恒假条件，表现为「所有人都看不到」而且不报错 ——
     * 是最难查的一类问题。调用方按顺序传 {@code (userId, now)}。</p>
     *
     * <p>maintainer 档的三个粗筛（库创建者 / 在维护名单里 / {@code maintain_scope=org_all}
     * 且是本组织活跃成员）是 {@code AccessServiceImpl#can(..., KB_EDIT_META)} 的<b>超集</b>
     * 而非复制：真正的裁决仍在权限服务，这里只把明显不可能的库先排除掉。已知偏差 ——
     * 组织管理员对 {@code owner_only} / {@code members} 档的库，门户上可能看不到
     * （他们在控制台里照常能管）。取舍是「门户少露一条」优于「在 AccessService 之外
     * 复制一份权限裁决」，后者违反规范 §2.6，且会随规则演进悄悄失效。</p>
     *
     * @param userId 当前用户 id；null 表示未登录访客
     */
    public static String portalScopeAllows(Long userId) {
        if (userId == null) {
            // 匿名访客只看得见 anonymous。member 与 maintainer 都需要一个身份。
            // 注意这里只用一个占位符 —— MyBatis-Plus 会校验「SQL 里的 {n} 个数 == 传入的参数个数」，
            // 多传一个会直接抛 Please check the syntax correctness。
            return "EXISTS (SELECT 1 FROM shares ps WHERE ps.kb_id = knowledge_base.id"
                    + " AND ps.scope = 'kb' AND ps.revoked = 0 AND ps.invalid = 0"
                    + " AND (ps.expires_at IS NULL OR ps.expires_at > {0})"
                    + " AND COALESCE(ps.portal_scope, 'anonymous') = 'anonymous')";
        }
        return "EXISTS (SELECT 1 FROM shares ps WHERE ps.kb_id = knowledge_base.id"
                + " AND ps.scope = 'kb' AND ps.revoked = 0 AND ps.invalid = 0"
                + " AND (ps.expires_at IS NULL OR ps.expires_at > {1})"
                // null 视为 anonymous：迁移前的老行没有这一列的值，读出来是 null
                + " AND (COALESCE(ps.portal_scope, 'anonymous') IN ('anonymous', 'member')"
                + "   OR (COALESCE(ps.portal_scope, 'anonymous') = 'maintainer' AND ("
                + "     knowledge_base.owner_id = {0}"
                + "     OR EXISTS (SELECT 1 FROM kb_member km WHERE km.kb_id = knowledge_base.id"
                + "       AND km.user_id = {0}"
                // 名单授权只在组织内生效（规范 §9）：必须带组织成员这一层，
                // 否则被移出组织后 kb_member 的行仍在库里，会成为静默的权限残留
                + "       AND EXISTS (SELECT 1 FROM tenant_member tm JOIN tenant t ON t.id = tm.tenant_id"
                + "            WHERE t.id = knowledge_base.tenant_id AND tm.user_id = {0} AND t.status = 'active'))"
                + "     OR (knowledge_base.maintain_scope = 'org_all'"
                + "       AND EXISTS (SELECT 1 FROM tenant_member tm2 JOIN tenant t2 ON t2.id = tm2.tenant_id"
                + "            WHERE t2.id = knowledge_base.tenant_id AND tm2.user_id = {0} AND t2.status = 'active'))"
                + "   )))";
    }

    /** 某个库的发布分享；没有（未分享 / 已撤销 / 已过期 / 只有单篇分享）返回 null。 */
    public Share find(Long kbId) {
        if (kbId == null) {
            return null;
        }
        return shareMapper.selectOne(activeKbScope().eq(Share::getKbId, kbId).last("LIMIT 1"));
    }

    /**
     * 一批库的发布分享：门户列表逐行判「公开还是私有」用，避免 N+1。
     *
     * <p>同一库理论上只该有一条整库分享（写入侧按 kb+目标复用已有行），这里用
     * {@code putIfAbsent} 兜住脏数据，取到哪条就以哪条为准。</p>
     */
    public Map<Long, Share> findOf(Collection<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Map.of();
        }
        List<Share> shares = shareMapper.selectList(activeKbScope().in(Share::getKbId, kbIds));
        Map<Long, Share> byKb = new HashMap<>();
        for (Share share : shares) {
            byKb.putIfAbsent(share.getKbId(), share);
        }
        return byKb;
    }

    /**
     * 发布态的三档判定：未发布 / 已发布共享 / 已发布加密。
     *
     * <p>「是否公开」与「要不要口令」在当前模型里是同一件事（{@code password_hash} 空否），
     * 所以门户侧只需要两档；控制台侧多出 {@link #PUBLISH_UNPUBLISHED} 一档，用于回答
     * 「我这个库到底发布了没有」。将来若要让免密与口令解耦，这里就是拆分点。</p>
     */
    public static String publishStatusOf(Share share) {
        if (share == null) {
            return PUBLISH_UNPUBLISHED;
        }
        return share.encrypted() ? ACCESS_PRIVATE : ACCESS_PUBLIC;
    }

    private LambdaQueryWrapper<Share> activeKbScope() {
        return Wrappers.<Share>lambdaQuery()
                .eq(Share::getScope, Share.SCOPE_KB)
                .eq(Share::getRevoked, 0)
                .eq(Share::getInvalid, 0)
                .and(w -> w.isNull(Share::getExpiresAt).or().gt(Share::getExpiresAt, TimeUtil.now()));
    }
}
