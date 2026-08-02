// agent-platform/backend/src/main/java/com/superprogrammer/common/exception/ErrorCode.java
package com.superprogrammer.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    // 通用错误
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "参数错误"),
    UNAUTHORIZED(401, "未认证"),
    TOKEN_EXPIRED(40101, "Access Token已过期，请使用Refresh Token刷新"),
    TOKEN_INVALID(40102, "Token已失效"),
    FORBIDDEN(403, "无权限"),
    ROLE_FORBIDDEN(40301, "角色权限不足"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    UNPROCESSABLE(422, "业务规则违反"),
    AGENT_NOT_PUBLISHED(42201, "Agent未发布"),
    WORKFLOW_INVALID(42202, "工作流结构无效"),
    AGENT_NO_SKILL(42203, "Agent无技能"),
    RATE_LIMIT(429, "请求频率超限"),
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;
}
