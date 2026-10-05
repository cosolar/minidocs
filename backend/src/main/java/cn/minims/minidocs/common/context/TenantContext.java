package cn.minims.minidocs.common.context;

import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.tenant.entity.Tenant;

/**
 * 请求级组织上下文（由 TenantInterceptor 填充，仅 {@code /api/console/{org}/**} 有值）。
 *
 * <p>组织上下文只从 URL 路径解析（规范 §3.4），不读 localStorage 也不读持久化的前端状态：
 * 刷新、收藏、两个标签页开两个组织才不会串。这里缓存解析结果，是为了让同一请求内的
 * 「解析 slug → 裁决」只查一次组织行，而不是每个 service 各查一次。</p>
 */
public final class TenantContext {

    private static final ThreadLocal<Tenant> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Tenant tenant) {
        HOLDER.set(tenant);
    }

    public static Tenant get() {
        return HOLDER.get();
    }

    /** 组织内接口的取值入口：没有组织上下文就是路由挂错了，直接 404 而不是 NPE。 */
    public static Tenant require() {
        Tenant tenant = HOLDER.get();
        if (tenant == null) {
            throw BizException.notFound("组织不存在");
        }
        return tenant;
    }

    public static Long requireId() {
        return require().getId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
