package cn.minims.minidocs.common.exception;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 上传体积超限的兜底。
 *
 * <p>必须单独一个 advice：{@link GlobalExceptionHandler} 限定只拦 {@code @RestController}，而体积超限是
 * 在 multipart 解析阶段抛出的 —— 那一刻 DispatcherServlet 还没解析出 handler，拿不到控制器类型，
 * 带注解条件的 advice 匹配不上，异常会掉给 DefaultHandlerExceptionResolver，前端只能看到一个没有
 * 响应体的裸 413（提示退化成 axios 的英文默认文案）。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class UploadSizeExceptionHandler {

    private final String maxFileSize;
    private final String maxRequestSize;

    public UploadSizeExceptionHandler(
            @Value("${spring.servlet.multipart.max-file-size:20MB}") String maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size:60MB}") String maxRequestSize) {
        this.maxFileSize = maxFileSize;
        this.maxRequestSize = maxRequestSize;
    }

    /** 提示里带上真实限额：只说「超过大小限制」，用户不知道该删到多少才过得去 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传体积超限：{}", e.getMessage());
        String message = "上传内容超过限制：单文件最多 " + maxFileSize + "，单次最多 " + maxRequestSize;
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiResponse<>(ErrorCode.FILE_TOO_LARGE.getCode(), message, null));
    }
}