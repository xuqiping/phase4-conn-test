package com.superprogrammer.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.logging.LogMasker;
import com.superprogrammer.common.security.SecurityEventPublisher;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作审计 AOP（日志系统 LOG-FR-10）：{@link AuditLog} 环绕通知。
 *
 * <p>环绕语义：成功 → result=SUCCESS 落库；异常 → result=FAIL + 错误摘要落库后<b>原样上抛</b>（不改业务语义）。
 * <p>detail_json 构建红线：参数值经 {@link LogMasker} 脱敏 + 单值截断 200 字符；
 * ServletRequest/Response/MultipartFile 等运行态参数跳过；序列化失败降级 "{}"。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private static final int VALUE_MAX_LEN = 200;

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 11x 加固 P3-C9：敏感动作成功发 KIND_PRIVILEGE_CHANGE（特权变更/凌晨敏感操作规则消费）。
     * 字段注入 required=false——横切可选依赖，单测无此 Bean 时跳过。
     */
    @Autowired(required = false)
    private SecurityEventPublisher securityEventPublisher;

    /** 敏感动作关键词（module:action 小写包含即命中）：提权/计费规则/安全开关/账号状态/封禁。 */
    private static final String[] SENSITIVE_KEYWORDS = {
            "role", "permission", "perm", "pricing", "billing", "status", "grant", "revoke",
            "block", "unblock", "ban", "setting", "unlock", "reset_password", "security"};

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        String targetId = firstLongArg(pjp.getArgs());
        String detail = buildDetail(pjp);
        try {
            Object result = pjp.proceed();
            doRecord(auditLog, targetId, detail, AuditLogEntity.RESULT_SUCCESS);
            publishPrivilegeChange(auditLog, targetId);
            return result;
        } catch (Throwable t) {
            // 失败也留痕（result=FAIL + 错误摘要），随后原样上抛
            String failDetail = detail.substring(0, detail.length() - 1)
                    + ",\"error\":\"" + LogMasker.mask(truncate(String.valueOf(t.getMessage()))) + "\"}";
            doRecord(auditLog, targetId, failDetail, AuditLogEntity.RESULT_FAIL);
            throw t;
        }
    }

    /** 11x P3-C9：敏感动作成功发特权变更事件（实时告警 + 凌晨检测；发事件失败吞，不阻主链）。 */
    private void publishPrivilegeChange(AuditLog auditLog, String targetId) {
        if (securityEventPublisher == null) {
            return;
        }
        try {
            String moduleAction = (auditLog.module() + ":" + auditLog.action()).toLowerCase();
            for (String kw : SENSITIVE_KEYWORDS) {
                if (!moduleAction.contains(kw)) {
                    continue;
                }
                Long userId = null;
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof Long uid) {
                    userId = uid;
                }
                securityEventPublisher.publish(ApplicationSecurityEvent.KIND_PRIVILEGE_CHANGE, userId,
                        Map.of("action", auditLog.module() + ":" + auditLog.action(),
                                "targetType", auditLog.targetType() == null ? "" : auditLog.targetType(),
                                "targetId", targetId == null ? "" : targetId));
                return;
            }
        } catch (Exception e) {
            log.warn("特权变更事件发布失败(已吞) : {}", e.getMessage());
        }
    }

    private void doRecord(AuditLog auditLog, String targetId, String detail, String result) {
        AuditLogEntity row = auditLogService.fromMdc(
                auditLog.module(), auditLog.action(), auditLog.targetType(), targetId, detail, result);
        row.setUserAgent(currentUserAgent());
        auditLogService.record(row);
    }

    /** 取第一个 Long 参数作 targetId（通常是 @PathVariable id）。 */
    private String firstLongArg(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Long id) {
                return String.valueOf(id);
            }
        }
        return null;
    }

    /** 参数摘要：{参数名: 脱敏截断值}；运行态参数（Servlet/Multipart）跳过。 */
    private String buildDetail(ProceedingJoinPoint pjp) {
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            String[] names = signature.getParameterNames();
            Object[] args = pjp.getArgs();
            Map<String, String> detail = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg == null || arg instanceof ServletRequest || arg instanceof ServletResponse
                        || arg instanceof MultipartFile) {
                    continue;
                }
                String name = names != null && i < names.length ? names[i] : "arg" + i;
                detail.put(name, LogMasker.mask(truncate(String.valueOf(arg))));
            }
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String value) {
        return value.length() <= VALUE_MAX_LEN ? value : value.substring(0, VALUE_MAX_LEN) + "...";
    }

    /** UA 从当前 web 请求取（@Scheduled 等非 web 线程调用则 null），截断 256。 */
    private String currentUserAgent() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String ua = attrs.getRequest().getHeader("User-Agent");
            if (ua != null) {
                return ua.length() <= 256 ? ua : ua.substring(0, 256);
            }
        }
        return null;
    }
}
