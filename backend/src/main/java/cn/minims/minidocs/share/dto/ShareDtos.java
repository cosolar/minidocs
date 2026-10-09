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

            /*
             * 门户曝光范围：anonymous（所有人）/ member（登录用户）/ maintainer（维护者）。
             * null = 不动现有值（更新时）。
             *
             * <p>用 @Pattern 而不是 @Enum：非法值若静默落回 anonymous，作者会以为设了某档、
             * 实际却是最宽的一档 —— 那比直接报错危险得多。</p>
             */
            @Pattern(regexp = "anonymous|member|maintainer",
                    message = "门户可见范围只能是 anonymous / member / maintainer") String portalScope,
            /**
             * 开源仓库地址；分享页顶栏的git 按钮跳到这里。留空 = 不配。
             *
             * <p>只放行 http/https：它最终会进 {@code href}，
             * {@code javascript:} 那类协议必须在这里挡掉，不能等到渲染时再判断。</p>
             */
            @Size(max = 512, message = "仓库地址不能超过 512 字符")
            @Pattern(regexp = "https?://\\S+$|^$",
                    message = "仓库地址必须以 http:// 或 https:// 开头")
            String repoUrl,
            /** 顶栏是否显示仓库按钮；null = 不动现有值 */
            Boolean showRepo,
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
            this(kbSlug, scope, docPath, password, encrypted, expiresIn, null, null, null, null, null);
        }
    }

    public record UpdateRequest(
            @Size(max = 64, message = "密码长度不能超过 64") String password,
            Boolean encrypted,
            String expiresIn,
            /** 门户曝光范围；null = 不动现有值 */
            @Pattern(regexp = "anonymous|member|maintainer",
                    message = "门户可见范围只能是 anonymous / member / maintainer") String portalScope,
            /**
             * 开源仓库地址；留空 = 不配。协议限制同 {@link CreateRequest}。
             */
            @Size(max = 512, message = "仓库地址不能超过 512 字符")
            @Pattern(regexp = "https?://\\S+$|^$",
                    message = "仓库地址必须以 http:// 或 https:// 开头")
            String repoUrl,
            /** 顶栏是否显示仓库按钮；null = 不动现有值 */
            Boolean showRepo) {

        /** 只改口令与有效期的旧调用点：门户曝光范围不动。 */
        public UpdateRequest(String password, Boolean encrypted, String expiresIn) {
            this(password, encrypted, expiresIn, null, null, null);
        }
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
                           String portalScope, List<NavMenuItem> menu,
                           /** 开源仓库地址；null = 未配置 */
                           String repoUrl,
                           /** 顶栏是否显示仓库按钮。恒为 false 当 {@code repoUrl} 为空 */
                           boolean showRepo) {

        public static ShareVO of(Share share, String url, KnowledgeBase kb, String docName,
                                  long uv, String expiresIn, boolean canGovern, List<NavMenuItem> menu) {
            return new ShareVO(share.getId(), share.getToken(), url, share.getKbId(),
                    kb == null ? null : kb.getSlug(), kb == null ? null : kb.getName(),
                    share.getScope(), share.getDocPath(), docName, share.encrypted(), expiresIn,
                    share.getExpiresAt(), share.getViews() == null ? 0 : share.getViews(), uv,
                    share.status(), share.getCreatedAt(), share.getUpdatedAt(), canGovern,
                    // null 归一为 anonymous：迁移前的老行没这一列，前端拿到 null 会显示成空档位
                    share.getPortalScope() == null ? Share.PORTAL_ALL : share.getPortalScope(),
                    menu,
                    // showRepo 在写入口已与 repoUrl 绑死（没地址不给开），这里再兜一次：
                    // 分享页是匿名可读的，一个「按钮在但不跳转」的入口最难解释
                    share.getRepoUrl(),
                    share.getRepoUrl() != null && Integer.valueOf(1).equals(share.getShowRepo()));
        }
    }
}
