package com.dcs4.ssoclient.config;

import java.util.Collections;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 保留本地 JSON 接口的错误响应；普通 HTML 页面沿用 Spring Boot 的错误处理。 */
@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> status(ResponseStatusException error) {
    log.warn("[ApiExceptionHandler] 业务状态异常: status={}, reason={}", error.getStatus(), error.getReason());
    return ResponseEntity.status(error.getStatus())
        .body(Collections.singletonMap("message", safeMessage(error.getReason())));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
    log.warn("[ApiExceptionHandler] 参数不合法: {}", e.getMessage());
    return ResponseEntity.badRequest()
        .body(Collections.singletonMap("message", "页面或协议参数不合法"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> failure(Exception error) {
    log.error("[ApiExceptionHandler] 系统未捕获异常", error);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Collections.singletonMap("message", "请求未完成，请联系管理员检查服务配置"));
  }

  private String safeMessage(String message) {
    if (message == null) {
      return "请求未完成";
    }
    return message;
  }
}
