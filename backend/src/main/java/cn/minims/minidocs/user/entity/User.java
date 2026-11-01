package cn.minims.minidocs.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_DISABLED = "disabled";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String displayName;

    private String email;

    /** 头像文件名（位于 minidocs.vault-home/avatars 下），未设置为 null */
    private String avatarUrl;

    /**
     * 邮箱是否已验证 —— 登录闸门只看这一位。
     *
     * <p>列的默认值是 1（见 {@code V11__email_verification.sql}），所以历史账号与
     * 「SMTP 没配、注册即放行」的账号都是 true。null 只出现在没查这一列的构造路径上，
     * 一律按已验证处理：闸门是防乱注册的，不该把正常用户挡在门外。</p>
     */
    private Boolean emailVerified;

    /** 待验证邮箱的一次性令牌（SHA-256 摘要，非原文）；验证成功或重发时被替换 */
    private String verifyToken;

    /** 令牌失效时间；过期后旧链接作废，用户需重新发送 */
    private LocalDateTime verifyExpiresAt;

    private String role;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean isAdmin() {
        return ROLE_ADMIN.equalsIgnoreCase(role);
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    /**
     * null 视为已验证，理由见 {@code emailVerified} 字段注释。
     *
     * <p>名字不能是 {@code isEmailVerified}：它会和 Lombok 给 {@code Boolean emailVerified}
     * 生成的 {@code getEmailVerified} 撞成同一个 JavaBeans 属性，而返回类型一个是
     * {@code boolean} 一个是 {@code Boolean} —— MyBatis 的反射层遇到「重载但不一致的 getter」
     * 会直接抛异常，退化成「所有涉及 users 表的读写全部失败」。</p>
     */
    public boolean hasVerifiedEmail() {
        return !Boolean.FALSE.equals(emailVerified);
    }
}
