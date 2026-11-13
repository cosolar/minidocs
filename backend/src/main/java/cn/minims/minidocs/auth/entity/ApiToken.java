package cn.minims.minidocs.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 第三方应用访问令牌（GitHub Personal Access Token 的做法）。
 *
 * <p>用途：让第三方应用凭一枚长期令牌访问官方接口，读到的数据与令牌所有者一致 ——
 * 令牌不做「另一个账号」，它就是所有者本人。</p>
 *
 * <p>只存摘要：{@link #tokenHash} 是原文的 SHA-256（十六进制），原文只在签发那一刻回吐一次，
 * 之后谁也拿不回来。因此服务端无法「重新显示」某个令牌，只能作废重发。</p>
 */
@Data
@TableName("api_token")
public class ApiToken {

    /** 只读：只能调 GET / HEAD / OPTIONS */
    public static final String SCOPE_READ = "read";
    /** 读写：可调写接口 */
    public static final String SCOPE_WRITE = "write";

    /** 令牌前缀：一眼认出是本平台签发的，不是登录 JWT */
    public static final String TOKEN_PREFIX = "mdt_";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 令牌所有者 */
    private Long userId;

    /** 应用名 / 用途，由本人填写 */
    private String name;

    /** SHA-256 摘要（十六进制），不是令牌原文 */
    private String tokenHash;

    /** 展示用前缀，如 {@code mdt_a1b2c3d4}，用来在列表里认出是哪一枚 */
    private String tokenPrefix;

    /** 逗号分隔的权限，恒含 {@link #SCOPE_READ} */
    private String scopes;

    /** 过期时间；为 null 表示长期有效 */
    private LocalDateTime expiresAt;

    /** 最近一次被使用的时间 */
    private LocalDateTime lastUsedAt;

    /** 吊销时间；非空即已作废 */
    private LocalDateTime revokedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean hasScope(String scope) {
        if (scopes == null || scopes.isBlank()) {
            return false;
        }
        for (String item : scopes.split(",")) {
            if (item.trim().equalsIgnoreCase(scope)) {
                return true;
            }
        }
        return false;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** 是否已过期（{@code now} 由调用方传入，便于测试固定时间）。 */
    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }
}
