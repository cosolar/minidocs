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

    /** 发布态：分享未加密，门户上无需口令即可阅读。 */
    public static final String ACCESS_PUBLIC = "public";
    /** 发布态：分享已加密，门户上需输入访问密码。 */
    public static final String ACCESS_PRIVATE = "private";

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

    /** 发布态的判定：未加密 = 公开，加密 = 私有。 */
    public static String accessOf(Share share) {
        if (share == null) {
            return null;
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
