package cn.minims.minidocs.auth.service;

import cn.minims.minidocs.auth.dto.ApiTokenDtos;
import cn.minims.minidocs.auth.dto.ApiTokenDtos.CreatedVO;
import cn.minims.minidocs.auth.dto.ApiTokenDtos.TokenVO;
import cn.minims.minidocs.user.entity.User;

import java.util.List;

/**
 * 第三方应用访问令牌。
 *
 * <p>令牌 = 所有者本人。持有令牌的应用能读到的东西，与所有者登录后能读到的一样，
 * 差别只在令牌可以被随时吊销、可以被限定为只读。</p>
 */
public interface ApiTokenService {

    /** 本人的令牌列表，新签发的在前。 */
    List<TokenVO> list(Long userId);

    /** 签发一枚新令牌；返回的 {@code token} 原文只在这里出现一次。 */
    CreatedVO create(Long userId, ApiTokenDtos.CreateRequest request);

    /** 吊销本人的令牌；令牌不存在或不属于本人时按「不存在」处理，不泄露他人是否有这枚令牌。 */
    void revoke(Long userId, Long tokenId);

    /**
     * 用令牌原文换取身份。
     *
     * <p>令牌无效、已吊销、已过期，或所有者账号已被禁用时，抛
     * {@code UNAUTHORIZED} / {@code ACCOUNT_DISABLED}。</p>
     */
    Authenticated authenticate(String rawToken);

    /**
     * 令牌认证的结果。
     *
     * @param user   令牌所有者
     * @param tokenId 本次用的是哪一枚令牌
     * @param scopes 该令牌的权限（逗号分隔）
     */
    record Authenticated(User user, Long tokenId, String scopes) {
    }
}
