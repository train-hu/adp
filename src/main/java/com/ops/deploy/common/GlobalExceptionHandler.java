package com.ops.deploy.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理：
 * - BizException：返回对应 code/message 的 Result；
 *   code 为标准 HTTP 状态码（400-599，如 401/403/404/500）时返回对应 HTTP 状态码，
 *   自定义业务码（如 5001+）时 HTTP 200 但 body code 非 0；
 * - MethodArgumentNotValidException（@Valid 校验失败）：HTTP 400 + body code=400，
 *   message 聚合所有字段错误，log.warn（客户端错误不打 error 级堆栈）；
 * - DataIntegrityViolationException：唯一约束冲突 → 409（如 name 唯一索引），
 *   外键约束冲突 → 500（引用数据缺失，属服务端状态问题，不可伪装成"名称已存在"误导用户）；
 * - 其他 Exception：500 兜底并 log.error（HTTP 200，body code=500）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException e) {
        if (e.getCode() >= 5000) {
            log.error("biz exception code={} message={}", e.getCode(), e.getMessage());
        } else {
            log.warn("biz exception code={} message={}", e.getCode(), e.getMessage());
        }
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) {
            status = HttpStatus.OK;
        }
        return ResponseEntity.status(status).body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (msg.isBlank()) {
            msg = "invalid request";
        }
        log.warn("validation failed: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ErrorCode.BAD_REQUEST, msg));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        // 23505 = unique_violation（客户端重复数据 → 409）；
        // 23503 = foreign_key_violation（引用数据缺失，服务端状态问题 → 500，不可伪装成唯一冲突）
        if (msg.contains("23505")) {
            log.warn("unique constraint violation: {}", firstLine(msg));
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.fail(ErrorCode.CONFLICT, "duplicate data, record already exists"));
        }
        log.error("data integrity violation (likely missing referenced data): {}", firstLine(msg));
        return ResponseEntity.ok(Result.fail(ErrorCode.SERVER_ERROR, "internal server error"));
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("unexpected exception", e);
        return ResponseEntity.ok(Result.fail(ErrorCode.SERVER_ERROR, "internal server error"));
    }
}
