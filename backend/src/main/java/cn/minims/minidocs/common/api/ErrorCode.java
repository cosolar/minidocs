package cn.minims.minidocs.common.api;

import lombok.Getter;

/**
 * 统一错误码（与需求文档 §8.1 错误码表一一对应）。
 */
@Getter
public enum ErrorCode {

    OK(0, 200, "ok"),
    PARAM_INVALID(40000, 400, "参数校验失败"),
    PATH_INVALID(40001, 400, "路径非法"),
    DEPTH_EXCEEDED(40002, 400, "路径深度超过 6 层"),
    FILE_TYPE_NOT_ALLOWED(40003, 400, "文件类型不允许"),
    GIT_NOT_BOUND(40004, 400, "该知识库未绑定 Git 仓库"),
    GIT_PARAM_INVALID(40005, 400, "Git 仓库配置不完整"),
    UNAUTHORIZED(40100, 401, "未登录或登录态失效"),
    FORBIDDEN(40300, 403, "无权限"),
    ACCOUNT_DISABLED(40301, 403, "账号被禁用 / 待审核"),
    NOT_FOUND(40400, 404, "资源不存在"),
    GONE(41000, 410, "资源已失效"),
    ALREADY_EXISTS(40900, 409, "资源已存在"),
    TARGET_EXISTS(40901, 409, "目标目录已存在同名项"),
    DOC_LOCKED(40902, 409, "文档正被他人编辑"),
    JOIN_REQUEST_PENDING(40903, 409, "已有待处理的入组申请"),
    TENANT_NOT_EMPTY(40904, 409, "组织下仍有知识库"),
    GIT_DIRTY(40905, 409, "本地有未提交的改动"),
    GIT_CONFLICT(40906, 409, "远程与本地存在冲突"),
    STALE_WRITE(41200, 412, "保存基线不一致，请刷新后重试"),
    FILE_TOO_LARGE(41300, 413, "文件超过大小限制"),
    LOGIN_TOO_FREQUENT(42901, 429, "登录尝试过于频繁"),
    SHARE_PWD_TOO_FREQUENT(42902, 429, "分享密码尝试过于频繁"),
    SERVER_ERROR(50000, 500, "服务端异常"),
    GIT_SYNC_FAILED(50200, 502, "Git 同步失败");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
