package cn.minims.minidocs.auth.service.impl;

import cn.minims.minidocs.auth.dto.ApiTokenDtos;
import cn.minims.minidocs.auth.dto.ApiTokenDtos.CreatedVO;
import cn.minims.minidocs.auth.dto.ApiTokenDtos.TokenVO;
import cn.minims.minidocs.auth.entity.ApiToken;
import cn.minims.minidocs.auth.mapper.ApiTokenMapper;
import cn.minims.minidocs.auth.service.ApiTokenService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 第三方应用访问令牌的落地实现。
 *
 * <p>三件事决定了这里的写法：</p>
 * <ol>
 *   <li><b>库里没有原文</b>：只存 SHA-256 摘要，所以「再显示一次」做不到，只能重发；</li>
 *   <li><b>吊销是标记不是删除</b>：{@code revoked_at} 一置位，令牌当场失效，但「曾经有过一枚令牌」
 *       这件事还留着；</li>
 *   <li><b>令牌不能自己发令牌</b>：{@code /api/tokens} 不接受令牌认证（见拦截器），
 *       否则一枚被复制出去的令牌就能无限增发。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTokenServiceImpl implements ApiTokenService {

    /** 一个人最多同时持有多少枚有效令牌。 */
    public static final int MAX_TOKENS_PER_USER = 20;

    /** 随机部分长度（GitHub 是 36 位，这里取 40）。 */
    private static final int RANDOM_LENGTH = 40;

    /** 展示前缀保留几位随机字符，够认出是哪一枚，又不至于泄底。 */
    private static final int DISPLAY_RANDOM = 8;

    /** 单个令牌允许的最长有效期（10 年）。 */
    public static final int MAX_EXPIRES_IN_DAYS = 3650;

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final ApiTokenMapper apiTokenMapper;
    private final UserService userService;

    private final SecureRandom random = new SecureRandom();

    @Override
    public List<TokenVO> list(Long userId) {
        List<ApiToken> tokens = apiTokenMapper.selectList(new LambdaQueryWrapper<ApiToken>()
                .eq(ApiToken::getUserId, userId)
                .orderByDesc(ApiToken::getCreatedAt)
                .orderByDesc(ApiToken::getId));
        return tokens.stream().map(token -> toVO(token, TimeUtil.now())).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreatedVO create(Long userId, ApiTokenDtos.CreateRequest request) {
        LocalDateTime now = TimeUtil.now();

        long alive = apiTokenMapper.selectCount(new LambdaQueryWrapper<ApiToken>()
                .eq(ApiToken::getUserId, userId)
                .isNull(ApiToken::getRevokedAt));
        if (alive >= MAX_TOKENS_PER_USER) {
            throw BizException.param("有效令牌最多 " + MAX_TOKENS_PER_USER + " 枚，请先吊销用不到的");
        }

        String raw = generateRaw();
        ApiToken entity = new ApiToken();
        entity.setUserId(userId);
        entity.setName(request.name().trim());
        entity.setTokenHash(sha256Hex(raw));
        entity.setTokenPrefix(raw.substring(0, ApiToken.TOKEN_PREFIX.length() + DISPLAY_RANDOM));
        entity.setScopes(normalizeScopes(request.scopes()));
        if (request.expiresInDays() != null) {
            entity.setExpiresAt(now.plusDays(request.expiresInDays()));
        }
        apiTokenMapper.insert(entity);

        log.info("签发访问令牌 [{}] owner={} scopes={} expiresAt={}",
                entity.getTokenPrefix(), userId, entity.getScopes(), entity.getExpiresAt());
        return new CreatedVO(raw, toVO(entity, now));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long userId, Long tokenId) {
        ApiToken token = apiTokenMapper.selectById(tokenId);
        // 不属于本人（或已被删）一律按「不存在」处理：不能让外人试探出某枚令牌是否存在
        if (token == null || !token.getUserId().equals(userId)) {
            throw BizException.notFound("令牌不存在");
        }
        if (token.isRevoked()) {
            return;
        }
        token.setRevokedAt(TimeUtil.now());
        apiTokenMapper.updateById(token);
        log.info("吊销访问令牌 [{}] owner={}", token.getTokenPrefix(), userId);
    }

    @Override
    public Authenticated authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        ApiToken token = apiTokenMapper.selectOne(new LambdaQueryWrapper<ApiToken>()
                .eq(ApiToken::getTokenHash, sha256Hex(rawToken))
                .last("LIMIT 1"));
        if (token == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "访问令牌无效");
        }
        LocalDateTime now = TimeUtil.now();
        if (token.isRevoked()) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "访问令牌已被吊销");
        }
        if (token.isExpired(now)) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "访问令牌已过期");
        }
        User user = userService.getById(token.getUserId());
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "访问令牌无效");
        }
        touchLastUsed(token, now);
        return new Authenticated(user, token.getId(), token.getScopes());
    }

    /** 记「最近一次被使用」；五分钟内不重复写，避免每个请求都落一次库。 */
    private void touchLastUsed(ApiToken token, LocalDateTime now) {
        if (token.getLastUsedAt() != null && token.getLastUsedAt().isAfter(now.minusMinutes(5))) {
            return;
        }
        token.setLastUsedAt(now);
        apiTokenMapper.updateById(token);
    }

    private TokenVO toVO(ApiToken token, LocalDateTime now) {
        String status = token.isRevoked() ? "revoked" : (token.isExpired(now) ? "expired" : "active");
        return new TokenVO(token.getId(), token.getName(), token.getTokenPrefix(), token.getScopes(),
                token.hasScope(ApiToken.SCOPE_WRITE), status, token.getCreatedAt(), token.getLastUsedAt(),
                token.getExpiresAt(), token.getRevokedAt());
    }

    /** 权限归一：只读是底，write 要显式要。 */
    private String normalizeScopes(String scopes) {
        if (scopes == null) {
            return ApiToken.SCOPE_READ;
        }
        for (String item : scopes.split(",")) {
            if (item.trim().equalsIgnoreCase(ApiToken.SCOPE_WRITE)) {
                return ApiToken.SCOPE_READ + "," + ApiToken.SCOPE_WRITE;
            }
        }
        return ApiToken.SCOPE_READ;
    }

    private String generateRaw() {
        StringBuilder sb = new StringBuilder(ApiToken.TOKEN_PREFIX.length() + RANDOM_LENGTH);
        sb.append(ApiToken.TOKEN_PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
