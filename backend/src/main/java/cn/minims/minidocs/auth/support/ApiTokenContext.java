package cn.minims.minidocs.auth.support;

import cn.minims.minidocs.auth.entity.ApiToken;

/**
 * 请求级令牌上下文：这次请求是不是用令牌认证的，以及这枚令牌带了哪些权限。
 *
 * <p>与 {@code UserContext} 并列：用户上下文回答「这是谁」，这里回答「这是谁用什么进来的」。</p>
 */
public final class ApiTokenContext {

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private ApiTokenContext() {
    }

    /** 一次令牌认证的身份。 */
    public record Principal(Long tokenId, String scopes) {

        public boolean hasWrite() {
            if (scopes == null) {
                return false;
            }
            for (String item : scopes.split(",")) {
                if (item.trim().equalsIgnoreCase(ApiToken.SCOPE_WRITE)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    public static Principal get() {
        return HOLDER.get();
    }

    /** 本次请求是否由令牌认证。 */
    public static boolean isApiToken() {
        return HOLDER.get() != null;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
