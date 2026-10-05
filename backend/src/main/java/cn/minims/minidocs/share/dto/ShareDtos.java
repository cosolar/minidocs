package cn.minims.minidocs.share.dto;

import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.reader.model.NavMenuItem;
import cn.minims.minidocs.share.entity.Share;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分享模块 DTO 集合。
 */
public final class ShareDtos {

    private ShareDtos() {
    }

    /**
     * 创建 / 更新分享。
     *
     * <p>目标库用 {@code kbSlug} 而不是 id：分享管理台、工作区「分享」按钮与地址栏都只认 slug
     * （规范 §4.2），传 id 会逼前端再存一份 id↔slug 映射。组织来自路径前缀，不在正文里重复。</p>
     */
    public record CreateRequest(
            @NotBlank(message = "知识库不能为空") String kbSlug,
            @Pattern(regexp = "kb|doc", message = "范围只能是 kb 或 doc") String scope,
            String docPath,
            @Size(max = 64, message = "密码长度不能超过 64") String password,
            Boolean encrypted,
            String expiresIn,
            /**
             * 导航菜单：null = 不动现有配置，空数组 = 清掉菜单。
             *
             * <p>区分这两者是因为同一个入口既要能「只改有效期」（前端不发这个字段），
             * 也要能「把菜单删光」（前端发空数组）。</p>
             */
            @Size(max = 40, message = "导航菜单最多 40 项") List<NavMenuItem> menu,
            /**
             * 自定义短链：留空 = 自动生成。仅在<b>首次创建</b>时生效，已经发出去的链接
             * 改短链等于让旧链接立刻失效，所以复��已有分享时改它会被拒绝。
             *
             * <p>格式与查重由服务层裁决（{@code ShareTokenUtil} + {@code uk_shares_token}），
             * 这里不重复校验：唯一性这件事依赖数据库，放 DTO 注解上只会让人误以为能靠注解解决。</p>
             */
            @Size(max = 32, message = "短链最长 32 位") String token) {

        /** 不带菜单与短链的便捷构造（组织隔离等旧调用点用）。 */
        public CreateRequest(String kbSlug, String scope, String docPath, String password,
                             Boolean encrypted, String expiresIn) {
            this(kbSlug, scope, docPath, password, encrypted, expiresIn, null, null);
        }
    }

    public record UpdateRequest(
            @Size(max = 64, message = "密码长度不能超过 64") String password,
            Boolean encrypted,
            String expiresIn) {
    }

    /**
     * 分享行。
     *
     * <p>{@code canGovern} 是「这一条我能不能改 / 撤销」的结论，由裁决层给出而不是前端按
     * owner 猜：撤销权看分享创建者与组织管理员（§2.4），前端两个都看不到。缺了它，列表里
     * 每一行的撤销按钮都是亮的，点下去一半 403。</p>
     */
    public record ShareVO(Long id, String token, String url, Long kbId, String kbSlug, String kbName,
                          String scope, String docPath, String docName, boolean encrypted, String expiresIn,
                          LocalDateTime expiresAt, int views, long uv, String status,
                          LocalDateTime createdAt, LocalDateTime updatedAt, boolean canGovern,
                          List<NavMenuItem> menu) {

        public static ShareVO of(Share share, String url, KnowledgeBase kb, String docName,
                                 long uv, String expiresIn, boolean canGovern, List<NavMenuItem> menu) {
            return new ShareVO(share.getId(), share.getToken(), url, share.getKbId(),
                    kb == null ? null : kb.getSlug(), kb == null ? null : kb.getName(),
                    share.getScope(), share.getDocPath(), docName, share.encrypted(), expiresIn,
                    share.getExpiresAt(), share.getViews() == null ? 0 : share.getViews(), uv,
                    share.status(), share.getCreatedAt(), share.getUpdatedAt(), canGovern, menu);
        }
    }
}
