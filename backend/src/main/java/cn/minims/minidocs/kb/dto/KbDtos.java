package cn.minims.minidocs.kb.dto;

import cn.minims.minidocs.common.util.PathEncoder;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.common.web.AppPaths;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.tenant.entity.Tenant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 知识库模块 DTO 集合。
 */
public final class KbDtos {

    private KbDtos() {
    }

    /**
     * 建库请求。
     *
     * <p>没有「建到哪个组织」这一项：组织只由路径 {@code /api/console/{org}/kbs} 决定，
     * 正文里再带一份会造出「路径说 A、正文说 B」的两可请求（规范 §4.2）。</p>
     *
     * <p>{@code maintainScope} 允许在建库时一次给定，是为了不逼出「建完再调一次维护档位」的两步写：
     * 第二步失败会留下一个档位与用户意图相反的库，而且没人会记得去补。省略即
     * {@code owner_only}（规范 §2.3 的保守默认）。</p>
     *
     * <p>{@code sourceType} 决定这个库的内容从哪来：{@code local}（默认）只建一个空目录，
     * {@code git} 则当场把 {@code gitUrl} 克隆成工作副本 —— 克隆失败整个建库就失败，
     * 不留一个「说好是云端、点进去却什么都没有」的半成品。凭证只在这里出现一次，
     * 之后既不入日志也不再回给前端。</p>
     */
    public record CreateRequest(
            @NotBlank(message = "知识库名称不能为空")
            @Size(max = 64, message = "名称长度不能超过 64") String name,
            @Size(max = 255, message = "描述长度不能超过 255") String description,
            @Pattern(regexp = "public|org|private", message = "可见性只能是 public / org / private") String visibility,
            @Pattern(regexp = "owner_only|members|org_all", message = "维护档位取值非法") String maintainScope,
            List<String> tags,
            String coverUrl,
            @Pattern(regexp = "local|git", message = "来源类型只能是 local / git") String sourceType,
            @Size(max = 512, message = "仓库地址过长") String gitUrl,
            @Size(max = 128, message = "分支名过长") String gitBranch,
            @Size(max = 128, message = "用户名过长") String gitUsername,
            @Size(max = 512, message = "访问令牌过长") String gitToken) {
    }

    public record UpdateRequest(
            @Size(max = 64, message = "名称长度不能超过 64") String name,
            @Size(max = 255, message = "描述长度不能超过 255") String description,
            @Pattern(regexp = "public|org|private", message = "可见性只能是 public / org / private") String visibility,
            List<String> tags,
            String coverUrl) {
    }

    /**
     * 知识库视图对象。
     *
     * <p>{@code tenantSlug} 与 {@code myPermissions} 是 v2 新增：URL 已按 {@code /kb/{org}/{slug}}
     * 组织，前端拼任何链接都要用到 org slug。{@code tenantName} 跟着一起给：门户是跨组织的
     * （规范 F10「列表标注所属组织」），只给 slug 的话两个同名库并排出现时读者分不出哪个是哪个，
     * 而让前端自己拿 slug 去猜显示名既猜不准（slug 可以是被截断过的）也得多一次请求。</p>
     *
     * <p>{@code myPermissions} 现在在<b>详情、列表、创建</b>三处都给 —— 列表页要按行决定「设置 / 删除 /
     * 新建」按钮该不该出现，只给详情就得逐行再请求一次。空数组的含义是「本次没算」而不是「什么都不能做」
     * （门户那几条链路仍走 4 参 {@link #from}，不带权限），前端据此判断，不能把空当成拒绝。
     * 判定一律经 {@code AccessService.permissionsOf}，没有第二套规则。</p>
     *
     * <p>代价说清楚：逐行裁决会为每行重查组织与名单事实（每行约 8 次主键级查询）。分页 size 上限 100、
     * 常规 20，实测在开发规模下可接受；真要压下来得让 {@code can*} 支持注入已解析事实，那是权限核心的
     * 重构，已单独记在开发计划里，不在这里偷偷复制判定。</p>
     *
     * <p>{@code maintainScope} 与 {@code visibility} 一起下发：规范 §1.2 把「谁能读」和「谁能写」定成
     * 两条正交的轴，UI 要同屏呈现两个下拉，只给一个就会让人以为改可见性也改了写权限。它是策略而不是
     * 秘密——看得到这个库的人本来就能看到自己的写权限结论。</p>
     */
    public record KbVO(Long id, Long ownerId, String ownerName, String name, String slug, String description,
                       String visibility, String maintainScope, String coverUrl, String coverSrc, List<String> tags,
                       Integer docCount, boolean favored, String shareStatus, String directoryPath,
                       LocalDateTime createdAt, LocalDateTime updatedAt, String updatedText,
                       String tenantSlug, String tenantName, List<String> myPermissions,
                       String sourceType, GitBindingVO git) {

        public static KbVO from(KnowledgeBase kb, boolean favored, String ownerName) {
            return from(kb, favored, ownerName, null);
        }

        /**
         * @param tenant 这个库所属的组织；传实体而不是只传 slug，是为了让 {@code tenantName} 与
         *               {@code tenantSlug} 出自同一次解析。列表务必用批量查好的 map，别在这里逐行查。
         */
        public static KbVO from(KnowledgeBase kb, boolean favored, String ownerName, Tenant tenant) {
            return from(kb, favored, ownerName, tenant, List.of());
        }

        public static KbVO from(KnowledgeBase kb, boolean favored, String ownerName, Tenant tenant,
                               List<KbAction> permissions) {
            String tenantSlug = tenant == null ? null : tenant.getSlug();
            String coverSrc = kb.getCoverUrl() == null || tenantSlug == null ? null
                    : AppPaths.of("/kb/" + tenantSlug + "/" + kb.getSlug() + "/asset/"
                    + PathEncoder.encodePath(kb.getCoverUrl()));
            List<String> granted = permissions == null ? List.of()
                    : permissions.stream().map(Enum::name).toList();
            return new KbVO(kb.getId(), kb.getOwnerId(), ownerName, kb.getName(), kb.getSlug(), kb.getDescription(),
                    kb.getVisibility(), kb.getMaintainScope(), kb.getCoverUrl(), coverSrc, splitTags(kb.getTags()),
                    kb.getDocCount() == null ? 0 : kb.getDocCount(), favored, null,
                    kb.getStorageKey() == null ? null : "/vaults/" + kb.getStorageKey(),
                    kb.getCreatedAt(), kb.getUpdatedAt(), TimeUtil.display(kb.getUpdatedAt()),
                    tenantSlug, tenant == null ? null : tenant.getName(), granted,
                    kb.getSourceType() == null ? KnowledgeBase.SOURCE_LOCAL : kb.getSourceType(),
                    GitBindingVO.of(kb));
        }

        /**
         * 补上门户发布态（{@code public} 未加密 / {@code private} 已加密）。
         *
         * <p>记录是不可变的，所以门户链路拿到 {@code from(...)} 之后再补这一格，而不是给
         * {@code from} 再加一个只有门户用得上的参数。非门户链路（后台管理台）不填，保持缺省。</p>
         */
        public KbVO withShareStatus(String shareStatus) {
            return new KbVO(id, ownerId, ownerName, name, slug, description, visibility, maintainScope, coverUrl,
                    coverSrc, tags, docCount, favored, shareStatus, directoryPath, createdAt, updatedAt, updatedText,
                    tenantSlug, tenantName, myPermissions, sourceType, git);
        }
    }

    /**
     * 云端知识库的绑定信息。
     *
     * <p>令牌只以 {@code tokenSet} 这个布尔回给前端 —— 它已经落在库里（密文），界面需要知道的
     * 只是「配过没有」，把可用的凭证再发一遍纯属多开一条泄露面。</p>
     */
    public record GitBindingVO(String url, String branch, String username, boolean tokenSet,
                               LocalDateTime lastSyncAt, String lastSyncStatus, Boolean lastSyncOk) {

        /** 非云端库返回 null，让序列化层（{@code non_null}）直接把整块略掉。 */
        public static GitBindingVO of(KnowledgeBase kb) {
            if (!kb.isGit()) {
                return null;
            }
            String token = kb.getGitToken();
            return new GitBindingVO(kb.getGitUrl(), kb.getGitBranch(), kb.getGitUsername(),
                    token != null && !token.isEmpty(),
                    kb.getGitLastSyncAt(), kb.getGitLastSyncStatus(), kb.getGitLastSyncOk());
        }
    }

    /** 云端库同步结果（拉取 / 提交推送共用一个形状）。 */
    public record GitSyncVO(boolean success, String message, int changedCount, String commitId,
                            GitBindingVO git) {
    }

    /** 提交推送的请求体。{@code message} 留空时由服务端给一句默认说明。 */
    public record GitSyncRequest(@Size(max = 200, message = "提交说明不能超过 200") String message) {
    }

    /** 云端库工作副本状态：界面上「有 N 处改动 / 落后 M 个提交」这些提示都出自这里。 */
    public record GitStatusVO(GitBindingVO git, boolean repository, boolean clean, int changedCount,
                              int ahead, int behind, String headCommit, List<String> changedPaths) {
    }


    /** 管理台统计卡片。三档可见性各自计数，不再用「总数减公开」冒充私有（规范 §2.2）。 */
    public record StatsVO(long kbTotal, long kbPublic, long kbOrg, long kbPrivate, long publicDocTotal,
                          long docTotal, long kbTotalDelta, long docTotalDelta) {
    }

    /**
     * 门户统计卡片。
     *
     * <p>分享等同于发布：门户只统计「已发布」（有效整库分享）的库，再按分享是否加密拆成
     * 公开 / 私有两档 —— 与 {@link #StatsVO} 的可见性三档不是一回事，所以另开一个形状，
     * 而不是给可见性那套硬塞两个语义不同的字段。</p>
     */
    public record PortalStatsVO(long kbTotal, long kbPublic, long kbPrivate, long docTotal,
                                long viewTotal, long viewDelta,
                                long kbTotalDelta, long docTotalDelta) {
    }

    /**
     * 维护名单授权。{@code role} 传 null 表示撤销该人的名单行。
     *
     * <p>值域只有 EDITOR / VIEWER：OWNER 不是名单里的概念，组织管理员与创建者的权限也不经名单（规范 §2.3）。</p>
     */
    public record RosterGrantRequest(
            @jakarta.validation.constraints.NotNull(message = "必须指定用户") Long userId,
            @Pattern(regexp = "EDITOR|VIEWER", message = "名单角色只能是 EDITOR / VIEWER") String role) {
    }

    /** 改维护档位。 */
    public record MaintainScopeRequest(
            @NotBlank(message = "维护档位不能为空")
            @Pattern(regexp = "owner_only|members|org_all", message = "维护档位取值非法") String maintainScope) {
    }

    /** 名单条目。{@code orgMember=false} 表示这行当前不产生任何权利（§9），UI 应提示而非静默。 */
    public record RosterEntryVO(Long userId, String username, String displayName, String role,
                                Long grantedBy, boolean orgMember, LocalDateTime createdAt) {
    }

    public static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        String joined = String.join(",", tags.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
        return joined.isBlank() ? null : joined;
    }
}
