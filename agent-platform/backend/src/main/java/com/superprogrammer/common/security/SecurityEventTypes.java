// agent-platform/backend/src/main/java/com/superprogrammer/common/security/SecurityEventTypes.java
package com.superprogrammer.common.security;

/**
 * 安全事件类型/严重度/自动响应码常量（11x 加固，spec 4.2 口径）。
 *
 * <p>检测规则码 15 个：6 热（暴破/撞库/SQLI/XSS/PATH/RATE_BURST）+ 9 冷（P3）。
 * 动作码 5 个：NONE/IP_BLOCKED/ACCOUNT_LOCKED/ACCOUNT_BANNED/TOKEN_REVOKED（响应结果，非检测规则）。
 */
public final class SecurityEventTypes {

    private SecurityEventTypes() {
    }

    // ---- 检测规则码（热路径，P2） ----
    /** 暴破：同账号 5 次失败/15min。 */
    public static final String LOGIN_BRUTE_FORCE = "LOGIN_BRUTE_FORCE";
    /** 撞库：同 IP 20 个不同账号/5min。 */
    public static final String CREDENTIAL_STUFFING = "CREDENTIAL_STUFFING";
    /** SQL 注入特征命中。 */
    public static final String SQLI_PROBE = "SQLI_PROBE";
    /** XSS 特征命中。 */
    public static final String XSS_PROBE = "XSS_PROBE";
    /** 路径穿越特征命中。 */
    public static final String PATH_PROBE = "PATH_PROBE";
    /** 限流屡犯：429 累计 3 次/1h。 */
    public static final String RATE_BURST = "RATE_BURST";
    /** 已封 IP 再次来访（命中黑名单仍继续请求）。 */
    public static final String IP_BLOCKED_HIT = "IP_BLOCKED_HIT";

    // ---- 检测规则码（冷路径，P3 用，先定义防散写） ----
    public static final String IDOR_PROBE = "IDOR_PROBE";
    public static final String IMPOSSIBLE_TRAVEL = "IMPOSSIBLE_TRAVEL";
    public static final String OFF_HOURS_SENSITIVE = "OFF_HOURS_SENSITIVE";
    public static final String DATA_EXFIL = "DATA_EXFIL";
    public static final String POINTS_ABUSE = "POINTS_ABUSE";
    public static final String MEDIA_ABUSE = "MEDIA_ABUSE";
    public static final String PROMPT_INJECTION = "PROMPT_INJECTION";
    /** KB 文档入库检出注入特征（安全体系 S3 · SEC-FR-051，文档隔离）。 */
    public static final String KB_INJECTION = "KB_INJECTION";
    /** LLM 输出含静态 system prompt 指纹（安全体系 S3 · SEC-FR-053，遮蔽+告警）。 */
    public static final String PROMPT_LEAK = "PROMPT_LEAK";
    public static final String TOKEN_REUSE = "TOKEN_REUSE";
    public static final String PRIVILEGE_CHANGE = "PRIVILEGE_CHANGE";

    // ---- 严重度 ----
    public static final String SEV_LOW = "LOW";
    public static final String SEV_MEDIUM = "MEDIUM";
    public static final String SEV_HIGH = "HIGH";
    public static final String SEV_CRITICAL = "CRITICAL";

    // ---- 自动响应动作码 ----
    public static final String ACT_NONE = "NONE";
    public static final String ACT_IP_BLOCKED = "IP_BLOCKED";
    public static final String ACT_ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String ACT_ACCOUNT_BANNED = "ACCOUNT_BANNED";
    public static final String ACT_TOKEN_REVOKED = "TOKEN_REVOKED";

    // ---- IP 封禁来源（ip_blacklist.source） ----
    public static final String SRC_AUTO = "AUTO";
    public static final String SRC_MANUAL = "MANUAL";
}
