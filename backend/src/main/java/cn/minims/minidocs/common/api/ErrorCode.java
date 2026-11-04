package cn.minims.minidocs.common.api;

import lombok.Getter;

/**
 * 统一错误码（与需求文档 §8.1 错误码表一一对应）。
 */
@Getter
public enum ErrorCode {

    OK(0, 200, "ok"),
    PARAM_INVALID(40000, 400, "填写内容有误，请检查后重试"),
    PATH_INVALID(40001, 400, "文件路径不合法"),
    DEPTH_EXCEEDED(40002, 400, "目录层级太深（最多 6 层）"),
    FILE_TYPE_NOT_ALLOWED(40003, 400, "不支持这种文件类型"),
    GIT_NOT_BOUND(40004, 400, "该知识库没有绑定线上仓库"),
    GIT_PARAM_INVALID(40005, 400, "线上仓库信息不完整"),
    VERIFY_LINK_INVALID(40006, 400, "验证链接无效或已过期"),
    UNAUTHORIZED(40100, 401, "未登录或登录态失效"),
    FORBIDDEN(40300, 403, "无权限"),
    ACCOUNT_DISABLED(40301, 403, "账号被禁用 / 待审核"),
    EMAIL_NOT_VERIFIED(40302, 403, "邮箱尚未验证"),
    NOT_FOUND(40400, 404, "资源不存在"),
    GONE(41000, 410, "资源已失效"),
    ALREADY_EXISTS(40900, 409, "资源已存在"),
    TARGET_EXISTS(40901, 409, "目标目录已存在同名项"),
    DOC_LOCKED(40902, 409, "文档正被他人编辑"),
    JOIN_REQUEST_PENDING(40903, 409, "已有待处理的入组申请"),
    TENANT_NOT_EMPTY(40904, 409, "组织下仍有知识库"),
    GIT_DIRTY(40905, 409, "有未保存的改动"),
    GIT_CONFLICT(40906, 409, "线上内容与本地改动有冲突"),
    STALE_WRITE(41200, 412, "内容已被其他人修改，请刷新后重试"),
    FILE_TOO_LARGE(41300, 413, "文件超过大小限制"),
    LOGIN_TOO_FREQUENT(42901, 429, "登录尝试过于频繁"),
    SHARE_PWD_TOO_FREQUENT(42902, 429, "分享密码尝试过于频繁"),
    RESEND_TOO_FREQUENT(42903, 429, "验证邮件发送过于频繁"),
    SERVER_ERROR(50000, 500, "系统开小差了，请稍后重试"),
    GIT_SYNC_FAILED(50200, 502, "内容同步失败"),
    MAIL_SEND_FAILED(50201, 502, "邮件发送失败");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
