package cn.minims.minidocs.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * /api/** 统一异常处理。仅拦截 @RestController，前端 SPA 的入口回退路由（@Controller）不受影响。
 */
@Slf4j
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBiz(BizException e, HttpServletRequest request) {
        ErrorCode code = e.getErrorCode();
        if (code.getHttpStatus() >= 500) {
            log.error("业务异常 [{}] {}", request.getRequestURI(), e.getMessage(), e);
        } else {
            log.warn("业务异常 [{}] code={} {}", request.getRequestURI(), code.getCode(), e.getMessage());
        }
        // data 携带恢复上下文（锁持有者、磁盘基线…）；绝大多数错误没有，就是 null
        return ResponseEntity.status(code.getHttpStatus())
                .body(new ApiResponse<>(code.getCode(), e.getMessage(), e.getPayload()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        return badRequest(collectErrors(e.getBindingResult()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException e) {
        return badRequest(collectErrors(e.getBindingResult()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        return badRequest(message.isBlank() ? ErrorCode.PARAM_INVALID.getMessage() : message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return badRequest(e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<ApiResponse<Void>> handleNoPermission(Exception e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(ErrorCode.FORBIDDEN));
    }

    /**
     * 请求体读不出来（JSON 语法错、字段类型不符、编码不对）。
     *
     * <p>这是客户端错误，落进 {@code handleOther} 会变成 500 并打整条堆栈日志。不回传 Jackson 的
     * 原始消息：它带 Java 类名与字段上下文，对调用方没用、对外人是线索。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e,
                                                              HttpServletRequest request) {
        log.warn("请求体解析失败 [{}]", request.getRequestURI());
        return badRequest("请求体格式错误");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e, HttpServletRequest request) {
        log.error("未处理异常 [{}]", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail(ErrorCode.SERVER_ERROR));
    }

    private String collectErrors(BindingResult result) {
        String message = result.getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining("; "));
        return message.isBlank() ? ErrorCode.PARAM_INVALID.getMessage() : message;
    }

    private String describe(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(ErrorCode.PARAM_INVALID.getCode(), message, null));
    }
}
