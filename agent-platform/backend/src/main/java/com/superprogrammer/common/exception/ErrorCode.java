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
    // 2x 待决策项（V100）：公共池项目关闭「允许复制」后，公共 VIEWER copy 被拒（文案不泄漏源项目信息）
    ASSET_COPY_FORBIDDEN(40302, "该项目不允许复制资产"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    UNPROCESSABLE(422, "业务规则违反"),
    AGENT_NOT_PUBLISHED(42201, "Agent未发布"),
    WORKFLOW_INVALID(42202, "工作流结构无效"),
    AGENT_NO_SKILL(42203, "Agent无技能"),
    RATE_LIMIT(429, "请求频率超限"),

    // 文件（安全体系 S1）
    FILE_TYPE_NOT_ALLOWED(40010, "文件类型不允许上传"),
    // 安全体系 S4 · SEC-FR-033 per-user 存储配额超限
    STORAGE_QUOTA_EXCEEDED(40011, "存储空间已满"),
    LOGIN_LOCKED(40103, "登录失败次数过多，请稍后再试"),
    // 安全体系 S2 · A8 单点登录（SEC-FR-008）：固定话术，不透传额外信息
    SESSION_KICKED(40104, "账号已在别处登录，请重新登录"),

    // 计费（402 Payment Required 桶）
    INSUFFICIENT_POINTS(40201, "积分余额不足，请联系管理员充值"),
    PRICING_NOT_FOUND(40202, "未配置该模型的价表，无法计费"),
    // 安全体系 S2 · L7 低余额并行闸门（SEC-FR-126）
    LOW_BALANCE_INFLIGHT_LIMIT(42902, "余额不足，请等待当前任务完成"),
    // 安全体系 S3 · SEC-FR-056 会话 token 上限（LLM10）：固定话术，不透传用量细节
    LLM_SESSION_CAP_EXCEEDED(42903, "本会话累计用量已达上限，请开通新会话继续"),
    // 2x 第三轮 C3 · 每用户媒体生成任务并发上限（15x 三问落地）：video/image 独立计数
    MEDIA_CONCURRENT_LIMIT(42904, "生成任务并发数已达上限，请等待在途任务完成后再试"),

    // 认证系统增强（多凭证账号模型）
    // 解绑时账号仅剩一种可用凭证 → 拒，防账号失联（找回密码/登录将无可用方式）
    CREDENTIAL_LAST_ONE(42204, "至少保留一种登录方式"),
    // 找回密码时，所选通道凭证 verified=FALSE（如注册填了邮箱但没点激活链接）→ 不发、提示先验证
    EMAIL_NOT_VERIFIED(42205, "该账号绑定的邮箱尚未验证，请先登录后在设置页验证邮箱"),
    // 短信验证码错误/过期（统一话术，不区分"码错"和"号不存在"，防枚举）
    SMS_CODE_INVALID(40105, "验证码错误或已过期"),
    // 找回密码 token 无效/过期（统一话术，防枚举）
    RESET_TOKEN_INVALID(40106, "重置链接/验证码无效或已过期"),
    // 滑块验证码校验失败（AJ-Captcha）
    CAPTCHA_INVALID(40107, "验证码校验失败，请重试"),
    // 凭证已被其他账号绑定（绑定冲突）
    CREDENTIAL_ALREADY_BOUND(40901, "该邮箱/手机号/微信已被其他账号绑定"),

    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;
}
