package com.example.feishuproxy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 兜底处理器。可预期的失败（未知机器人、限流、上游错误）都以结果对象的形式携带，
 * 由控制器负责答复；只有真正意想不到的异常才会到这里。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> onUnreadableBody(HttpMessageNotReadableException e) {
        return JsonResponses.error(objectMapper, 400, 40001, "invalid request body");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> onWrongMethod(HttpRequestMethodNotSupportedException e) {
        return JsonResponses.error(objectMapper, 405, 40501, "method not allowed: " + e.getMethod());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> onUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return JsonResponses.error(objectMapper, 500, 50000, "internal error");
    }
}
