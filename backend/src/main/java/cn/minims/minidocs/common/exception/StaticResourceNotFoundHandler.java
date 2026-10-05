package cn.minims.minidocs.common.exception;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 未映射路径与缺失静态资源的 404。
 *
 * <p>{@link GlobalExceptionHandler} 用 {@code annotations = RestController.class} 把自己限定在
 * 接口控制器上，而 {@code /**} 资源处理器抛出的 {@code NoResourceFoundException} 没有 handler 类型，
 * 落不进那个 advice —— 结果是打错一个一级路径就返回 500 白页。这里补一个不限定作用域的窄 advice，
 * 只处理这一个异常；其它异常的归属口径不变。</p>
 */
@Slf4j
@Order(100)
@RestControllerAdvice
public class StaticResourceNotFoundHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        log.debug("未找到资源：{}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(ErrorCode.NOT_FOUND));
    }
}
