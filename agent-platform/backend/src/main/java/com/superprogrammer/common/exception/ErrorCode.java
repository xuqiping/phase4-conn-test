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

    // 文件（安全体系 S1）
    FILE_TYPE_NOT_ALLOWED(40010, "文件类型不允许上传"),
    LOGIN_LOCKED(40103, "登录失败次数过多，请稍后再试"),
    // 安全体系 S2 · A8 单点登录（SEC-FR-008）：固定话术，不透传额外信息
    SESSION_KICKED(40104, "账号已在别处登录，请重新登录"),

    // 计费（402 Payment Required 桶）
    INSUFFICIENT_POINTS(40201, "积分余额不足，请联系管理员充值"),
    PRICING_NOT_FOUND(40202, "未配置该模型的价表，无法计费"),
    // 安全体系 S2 · L7 低余额并行闸门（SEC-FR-126）
    LOW_BALANCE_INFLIGHT_LIMIT(42902, "余额不足，请等待当前任务完成"),

    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;
}
