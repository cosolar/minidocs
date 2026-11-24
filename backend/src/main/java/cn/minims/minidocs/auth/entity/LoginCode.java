package cn.minims.minidocs.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个邮箱当前待用的登录验证码。
 *
 * <p>与注册验证码同一套形状：email 做主键，同一邮箱重发即覆盖，旧码随之失效；
 * 登录成功或试错超限后整行删除，不留可重放的凭据。</p>
 */
@Data
@TableName("login_code")
public class LoginCode {

    /** 收码邮箱（已小写归一），同时是主键 */
    @TableId(value = "email", type = IdType.INPUT)
    private String email;

    /** SHA-256(email + ':' + code) 十六进制，不存原文 */
    private String codeHash;

    /** 已试错次数，达到上限即整行作废 */
    private Integer attempts;

    private LocalDateTime expiresAt;

    /**
     * 非空表示「刚刚用验证码登录过，允许免原密码重设密码」的一次性许可。
     *
     * <p>用码登录成功后写入，重设成功、重新发码或用密码正常登录时整行删除。</p>
     */
    private LocalDateTime resetExpiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
