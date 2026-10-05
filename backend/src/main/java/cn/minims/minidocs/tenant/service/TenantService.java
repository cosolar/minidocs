package cn.minims.minidocs.tenant.service;

import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.tenant.dto.TenantDtos.CreateOrgRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.DiscoverVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.OrgVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.TenantBrief;
import cn.minims.minidocs.tenant.dto.TenantDtos.UpdateOrgRequest;
import cn.minims.minidocs.tenant.entity.Tenant;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;

/**
 * 组织（租户）本身的生命周期：建、看、改、删、被发现。
 *
 * <p>成员与入组申请在 {@code TenantMemberService}：本类只管 {@code tenant} 一行，
 * 成员规则（I1 最后一个 OWNER）需要连着 {@code tenant_member} 一起判，放这里会让两类规则互相穿插。</p>
 */
public interface TenantService extends IService<Tenant> {

    /**
     * 幂等取得用户的个人组织，缺失则创建（同时写入 OWNER 成员行）。
     *
     * <p>既服务注册流程，也兜住 V2 迁移之后新建、但尚未建过库的用户。</p>
     */
    Tenant ensurePersonalTenant(Long userId);

    Tenant findBySlug(String slug);

    /**
     * 按 slug 取组织，取不到即 404（含停用与个人组织的 slug 解析规则）。
     *
     * <p>只做「存在性」而不做权限：调用方紧接着要判的动作各不相同，在这里合并判定会让
     * {@code /console/{org}/**} 的所有入口共用一份错误的口径。</p>
     */
    Tenant requireBySlug(String slug);

    /** 创建团队组织；创建者成为 OWNER。 */
    OrgVO createTeam(CreateOrgRequest request, LoginUser actor);

    /** 组织详情（含访问者自己的角色与计数）。 */
    OrgVO detail(String slug, LoginUser actor);

    /** 我参与的组织（含个人组织），供顶栏切换器渲染。 */
    List<TenantBrief> myTenants(LoginUser actor);

    /** 改名称 / 简介 / 加入方式 / 是否可被发现（OWNER）。 */
    OrgVO update(String slug, UpdateOrgRequest request, LoginUser actor);

    /**
     * 删除组织（OWNER）。个人组织禁止（I3）；组织内还有知识库时拒绝 ——
     * 删组织要连带删磁盘目录，那是不可逆操作，必须由「先清库、再删组织」两步走。
     */
    void delete(String slug, LoginUser actor);

    /** 组织发现列表：TEAM + 可被发现 + 活跃，只出公开字段。 */
    PageResult<DiscoverVO> discover(long page, long size);
}
