// agent-platform/backend/src/main/java/com/superprogrammer/common/exception/GlobalExceptionHandler.java
package com.superprogrammer.common.exception;

import com.superprogrammer.common.result.R;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 11x 加固 P3-C9：403 咽喉发 KIND_AUTHZ_DENIED（IDOR 探测规则消费）。
     * ObjectProvider 延迟解析——@WebMvcTest 切片不加载普通 Bean 时 null → 跳过发事件（切片兼容红线）。
     */
    private final ObjectProvider<ApplicationEventPublisher> eventPublisherProvider;

    public GlobalExceptionHandler(ObjectProvider<ApplicationEventPublisher> eventPublisherProvider) {
        this.eventPublisherProvider = eventPublisherProvider;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        HttpStatus status = resolveHttpStatus(e.getCode());
        // 11x 加固 P4 修复：@RequirePermission 权限不足抛 BusinessException(FORBIDDEN)（非 Spring
        // AccessDeniedException），原只 handleAccessDeniedException 发事件 → IdorProbe 规则拿不到
        // 最常见 403 来源。此处补：BusinessException 映射为 HTTP 403 时同样发 KIND_AUTHZ_DENIED。
        if (status == HttpStatus.FORBIDDEN) {
            publishAuthzDenied(request);
        }
        return ResponseEntity.status(status).body(R.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("参数校验失败: {}", errors);
        return ResponseEntity.badRequest().body(R.fail(400, "参数校验失败: " + errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraintViolationException(
            ConstraintViolationException e) {
        log.warn("约束校验失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(R.fail(400, e.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<R<Void>> handleBadCredentialsException(BadCredentialsException e) {
        log.warn("认证失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(R.fail(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDeniedException(AccessDeniedException e,
                                                               HttpServletRequest request) {
        log.warn("权限不足: {}", e.getMessage());
        publishAuthzDenied(request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(R.fail(ErrorCode.FORBIDDEN));
    }

    /** 11x 加固 P3-C9：403 发 KIND_AUTHZ_DENIED（payload: uri/method）。发事件失败吞，不阻 403 响应。 */
    private void publishAuthzDenied(HttpServletRequest request) {
        try {
            ApplicationEventPublisher publisher = eventPublisherProvider.getIfAvailable();
            if (publisher == null) {
                return;
            }
            Long userId = null;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Long uid) {
                userId = uid;
            }
            publisher.publishEvent(ApplicationSecurityEvent.of(this,
                    ApplicationSecurityEvent.KIND_AUTHZ_DENIED, userId,
                    request == null ? null : request.getRemoteAddr(),
                    Map.of("uri", request == null ? "" : request.getRequestURI(),
                            "method", request == null ? "" : request.getMethod())));
        } catch (Exception ex) {
            log.warn("403 安全事件发布失败(已吞) : {}", ex.getMessage());
        }
    }

    /**
     * 唯一约束冲突（重名/重复提交）→ 409 友好提示，而非 500「未预期异常」。
     * 解析 PG 原始错误信息，提取约束名 + 冲突键值，给用户/开发者可读的提示。
     */
    @ExceptionHandler({DuplicateKeyException.class, DataIntegrityViolationException.class})
    public ResponseEntity<R<Void>> handleDuplicateKey(DataIntegrityViolationException e) {
        String root = extractRootMessage(e);
        // 非空约束违例（null value in column ...）不是「唯一约束冲突」，单列分支给准确提示，
        // 否则下面的 extractConstraintName 会把列名误当约束名报「唯一约束：amount_yuan」。
        if (root != null && (root.contains("null value") || root.toLowerCase().contains("not-null"))) {
            String col = extractNotNullColumn(root);
            log.warn("非空约束违例: column={}, root={}", col, root);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(R.fail(400, "必填字段为空" + (col != null ? "（" + col + "）" : "") + "，请补全后重试"));
        }
        String constraint = extractConstraintName(root);
        String friendly = buildDuplicateFriendlyMessage(constraint);
        log.warn("唯一约束冲突: constraint={}, root={}", constraint, root);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(R.fail(ErrorCode.CONFLICT.getCode(), friendly));
    }

    /** 从 PG 非空违例信息提取列名，如 `null value in column "amount_yuan" of relation ...` → amount_yuan。 */
    private String extractNotNullColumn(String msg) {
        Matcher m = Pattern.compile("column \"([^\"]+)\"").matcher(msg);
        return m.find() ? m.group(1) : extractConstraintName(msg);
    }

    /** 从 PG 错误信息提取约束名，如 "uk_kb_tenant_name" */
    private String extractConstraintName(String msg) {
        if (msg == null) return null;
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(msg);
        return m.find() ? m.group(1) : null;
    }

    /** 约束名 → 中文友好提示；未命中给通用话术（含约束名便于排查） */
    private String buildDuplicateFriendlyMessage(String constraint) {
        if (constraint == null) return "数据已存在，请勿重复创建";
        return switch (constraint) {
            case "uk_kb_tenant_name" -> "同名知识库已存在，请更换名称";
            case "uk_user_memories_user_key_home" -> "该记忆已存在，请勿重复添加";
            case "uk_users_username" -> "用户名已存在，请更换";
            case "uk_pgm_group_user" -> "该用户已在项目组中，请勿重复添加";
            default -> "数据已存在（唯一约束：" + constraint + "），请勿重复创建";
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleException(Exception e) {
        // 安全审计 #7：兜底异常 message 可能含 SQL/表名/类名/文件路径/堆栈——直接回客户端等于递情报。
        // 客户端固定话术；完整 root message 仅写后端 ERROR 日志供排查。
        // 注：BusinessException 走专用 handler，其 message 是受控业务话术，可回显。
        String detail = extractRootMessage(e);
        log.error("未预期异常: {}", detail, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(500, "服务器内部错误，请稍后重试"));
    }

    /**
     * 提取异常根因消息，避免只返回 "服务器内部错误" 这种无意义信息
     */
    private String extractRootMessage(Throwable e) {
        Throwable cause = e;
        // 最多追溯5层
        for (int i = 0; i < 5 && cause.getCause() != null && cause.getCause() != cause; i++) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg;
    }

    /**
     * 业务码 → HTTP 状态映射。5 位业务码约定「前三位 = HTTP 语义」：
     * 40010→400、40103→401、40201→402、40301→403、42202→422。
     * （安全体系 S1 顺手修既有 F4：40201 曾落 500；RATE_LIMIT 429 曾落 400。）
     */
    private HttpStatus resolveHttpStatus(int code) {
        if (code == 200) return HttpStatus.OK;
        int http = code >= 10000 ? code / 100 : code;
        return switch (http) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 402 -> HttpStatus.PAYMENT_REQUIRED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
