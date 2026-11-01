package cn.minims.minidocs.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个邮箱当前待用的注册验证码。
 *
 * <p>注册完成前还没有用户行，验证码只能独立存；email 做主键，同一邮箱重发即覆盖，
 * 旧码随之失效。校验通过后整行删除，不留可重放的凭据。</p>
 */
@Data
@TableName("register_code")
public class RegisterCode {

    /** 收码邮箱（已小写归一），同时是主键 */
    @TableId(value = "email", type = IdType.INPUT)
    private String email;

    /** SHA-256(email + ':' + code) 十六进制，不存原文 */
    private String codeHash;

    /** 已试错次数，达到上限即整行作废 */
    private Integer attempts;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
