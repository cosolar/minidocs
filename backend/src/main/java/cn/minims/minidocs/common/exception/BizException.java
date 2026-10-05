package cn.minims.minidocs.common.exception;

import cn.minims.minidocs.common.api.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：携带统一错误码。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 随错误一起回给客户端的结构化上下文（409 的锁持有者、412 的磁盘基线）。
     *
     * <p>只放客户端据以恢复的最小信息，绝不放正文或敏感字段。</p>
     */
    private Object payload;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 挂上恢复上下文：链式调用，抛出处读起来仍是一句话。 */
    public BizException payload(Object payload) {
        this.payload = payload;
        return this;
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }

    public static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, message);
    }

    public static BizException param(String message) {
        return new BizException(ErrorCode.PARAM_INVALID, message);
    }

    public static BizException exists(String message) {
        return new BizException(ErrorCode.ALREADY_EXISTS, message);
    }
}
