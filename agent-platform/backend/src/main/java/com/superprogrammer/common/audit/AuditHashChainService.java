package com.superprogrammer.common.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 审计哈希链服务（安全体系 S2 · D1，SEC-FR-040）：audit_logs 每行带 prev_hash/record_hash，
 * HMAC-SHA256 链式写入——任何单行篡改/删除/插行都会使该处及之后全链证伪（D3 校验发现）。
 *
 * <p>写入串行化：{@code pg_advisory_xact_lock} 包住「读末行 hash → 插新行」，防并发分叉双链。
 * 审计本就是异步单消费者小池（core1/max2），锁竞争极低，且不碰请求线程。
 *
 * <p>密钥红线：{@code AUDIT_HMAC_KEY} 仅环境变量注入，启动缺失/过短拒绝启动（fail-fast 同
 * JwtUtil 范式）——密钥进代码/配置仓库等于链可伪造。密钥轮换后旧链仍可验（密钥只影响
 * 重算比对，轮换即旧链失效告警，故轮换须配合全量校验+新锚定，入 G1 轮换清单）。
 *
 * <p>存量行（V81 前）prev_hash/record_hash 为 NULL=链外，由 V78 REVOKE 物理防篡改兜底；
 * 首条链上行 prev_hash=GENESIS。
 */
@Slf4j
@Service
public class AuditHashChainService {

    /** 链起点哨兵：首条链上行的 prev_hash。 */
    static final String GENESIS = "GENESIS";

    /** 咨询锁固定键（全库唯一约定，勿与他处冲突）。 */
    static final long ADVISORY_LOCK_KEY = 810_202_608L;

    /** 字段分隔用不可输入控制符，防字段值拼接歧义（canonical 防第二原像）。 */
    private static final String FS = "\u0001";

    private final AuditLogMapper auditLogMapper;
    private final String hmacKey;

    public AuditHashChainService(AuditLogMapper auditLogMapper,
                                 @Value("${audit.hmac-key:}") String hmacKey) {
        this.auditLogMapper = auditLogMapper;
        this.hmacKey = hmacKey == null ? "" : hmacKey.trim();
    }

    /** 启动校验：密钥缺失或过短 → 拒绝启动（fail-fast > 静默跑无链审计）。 */
    @PostConstruct
    void validateKey() {
        if (hmacKey.isBlank()) {
            throw new IllegalStateException(
                    "AUDIT_HMAC_KEY 未配置：审计哈希链（SEC-FR-040）要求经环境变量注入 HMAC 密钥"
                            + "（生成: openssl rand -base64 48 | tr -d '\\n'）。禁止使用空密钥启动。");
        }
        if (hmacKey.length() < 32) {
            throw new IllegalStateException("AUDIT_HMAC_KEY 长度不足（>=32 字符）。请注入强随机密钥后重启。");
        }
    }

    /**
     * 链式插入一条审计行（在审计异步池线程内调用，失败由调用方按 fire-and-forget 降级）。
     * <p>created_at 在此显式赋值为 UTC 微秒精度——DB DEFAULT 在插入后才确定，且 pgjdbc 读回
     * timestamptz 恒为 UTC offset+微秒精度，写入侧必须同型归一，否则校验重算恒断链。
     */
    @Transactional(rollbackFor = Exception.class)
    public void insertChained(AuditLogEntity row) {
        auditLogMapper.advisoryLock(ADVISORY_LOCK_KEY);
        String last = auditLogMapper.selectLastRecordHash();
        String prev = (last == null || last.isBlank()) ? GENESIS : last;
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        }
        row.setPrevHash(prev);
        row.setRecordHash(hmacHex(canonical(row) + prev));
        auditLogMapper.insert(row);
    }

    /** 校验单行：按行内 prev_hash 重算 record_hash 比对（D3 链校验复用）。 */
    boolean matches(AuditLogEntity row) {
        if (row.getRecordHash() == null || row.getPrevHash() == null) {
            return false;
        }
        return hmacHex(canonical(row) + row.getPrevHash()).equals(row.getRecordHash());
    }

    /** JSON 解析器：浮点走 BigDecimal 保字面值精度（toPlainString 输出，两侧一致）。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /**
     * 规范化行内容：全字段（除 id/prev/record_hash）按固定顺序 + 控制符分隔，null→空串。
     * <p>id 不入哈希：插入时 id 由 DB IDENTITY 生成尚不存在，行位置已由 prev_hash 链绑定。
     * <p>detailJson 走 {@link #canonicalJson}：jsonb 落库会被 PG 规整（键重排/冒号空格），
     * 直接哈希原文会导致校验读回恒不匹配；两侧统一「解析成树→确定性重排输出」后一致。
     */
    static String canonical(AuditLogEntity r) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(r.getCreatedAt() == null ? "" : r.getCreatedAt().toString()).append(FS)
          .append(n(r.getTraceId())).append(FS)
          .append(n(r.getUserId())).append(FS)
          .append(n(r.getUsername())).append(FS)
          .append(n(r.getModule())).append(FS)
          .append(n(r.getAction())).append(FS)
          .append(n(r.getTargetType())).append(FS)
          .append(n(r.getTargetId())).append(FS)
          .append(canonicalJson(r.getDetailJson())).append(FS)
          .append(n(r.getClientIp())).append(FS)
          .append(n(r.getUserAgent())).append(FS)
          .append(n(r.getResult()));
        return sb.toString();
    }

    /**
     * JSON 规范化：解析成树后确定性重排输出（对象键按 UTF-8 字典序、无空格、数字 toPlainString）。
     * 插入侧（原始 JSON 串）与校验侧（PG jsonb 读回串）解析出同一棵树 → 同一串。
     * 非 JSON（解析失败）原样返回（两侧同样处理，仍一致）。
     */
    static String canonicalJson(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            return emitJson(JSON.readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    private static String emitJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                entries.add(it.next());
            }
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<String, JsonNode> e : entries) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append(emitString(e.getKey())).append(':').append(emitJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(emitJson(node.get(i)));
            }
            return sb.append(']').toString();
        }
        if (node.isNumber()) {
            return node.decimalValue().toPlainString();
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? "true" : "false";
        }
        return emitString(node.asText());
    }

    /** 字符串 JSON 转义输出（Jackson writeValueAsString 等价，保非 ASCII 原字符，两侧一致）。 */
    private static String emitString(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s + "\"";
        }
    }

    private static String n(Object v) {
        return v == null ? "" : v.toString();
    }

    private String hmacHex(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // HmacSHA256 为 JDK 必含算法，理论不可达；兜底转 500 而非静默写坏链
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }
}
