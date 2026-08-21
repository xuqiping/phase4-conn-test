package com.superprogrammer.billing.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.dto.PaymentChannelConfigVO;
import com.superprogrammer.billing.entity.PaymentChannelConfigEntity;
import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.billing.mapper.PaymentChannelConfigMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.llm.service.AesEncryptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付渠道网页配置（7x 追加，V143/V144）。
 *
 * <p>安全口径：
 * <ul>
 *   <li>密钥整体 AES 加密落库（复用 LLM Key 同管线 {@link AesEncryptService}），明文不入库；</li>
 *   <li>读取端点只出 {@code config_tails} 脱敏尾巴，永不解密出库；</li>
 *   <li>保存=merge 语义：留空字段保持原值，改一键不需重填全部；merge 后校验必填全集；</li>
 *   <li>渠道码 + 字段键双白名单，非法键 400（防任意键注入）。</li>
 * </ul>
 *
 * <p>{@link #getDecrypted(String)} 为内部桥（DB 优先、env 兜底）——支付宝/微信 SDK 实现落地时
 * 由渠道类调用；当前渠道 available() 恒 false（骨架期），配置齐全不会把未实现渠道暴露给用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentChannelConfigService {

    /** 渠道 → 必填键白名单（顺序即前端表单顺序）。 */
    public static final Map<String, List<String>> REQUIRED_KEYS = Map.of(
            PaymentOrderEntity.CHANNEL_ALIPAY, List.of("appId", "privateKey", "alipayPublicKey"),
            PaymentOrderEntity.CHANNEL_WECHAT, List.of("mchId", "appId", "apiV3Key")
    );

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final PaymentChannelConfigMapper configMapper;
    private final AesEncryptService aesEncryptService;
    private final ObjectMapper objectMapper;

    // ---- env 兜底（DB 无配置时 SDK 读取顺序的第二级；application.yml 占位同名） ----
    @Value("${billing.payment.alipay.app-id:}") private String envAlipayAppId;
    @Value("${billing.payment.alipay.private-key:}") private String envAlipayPrivateKey;
    @Value("${billing.payment.alipay.alipay-public-key:}") private String envAlipayPublicKey;
    @Value("${billing.payment.wechat.mch-id:}") private String envWechatMchId;
    @Value("${billing.payment.wechat.app-id:}") private String envWechatAppId;
    @Value("${billing.payment.wechat.api-v3-key:}") private String envWechatApiV3Key;

    // ---------- admin 读（脱敏） ----------

    /** 两渠道脱敏配置状态（未配置渠道也出行，configured=false，tails 空）。 */
    public List<PaymentChannelConfigVO> listMasked() {
        return REQUIRED_KEYS.keySet().stream().sorted().map(channel -> {
            PaymentChannelConfigEntity e = configMapper.selectByChannel(channel);
            if (e == null) {
                return new PaymentChannelConfigVO(channel, false, Map.of(), null, null);
            }
            return new PaymentChannelConfigVO(channel, true, parseMap(e.getConfigTails()),
                    e.getUpdatedAt() != null ? e.getUpdatedAt() : e.getCreatedAt(), e.getUpdatedBy());
        }).toList();
    }

    // ---------- admin 写（merge + 加密） ----------

    /**
     * 保存渠道配置（merge：incoming 中空白字段保持原值）。
     *
     * @throws BusinessException 未知渠道/未知键 400；merge 后必填键不全 400
     */
    public void save(String channel, Map<String, String> incoming) {
        List<String> required = REQUIRED_KEYS.get(channel);
        if (required == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未知支付渠道: " + channel);
        }
        Map<String, String> merged = new LinkedHashMap<>();
        // 既有配置先垫底（解密仅在写路径发生，不出库）
        PaymentChannelConfigEntity existing = configMapper.selectByChannel(channel);
        if (existing != null) {
            merged.putAll(decryptToMap(existing.getConfigEncrypted()));
        }
        // 覆盖：白名单内且非空白的键
        if (incoming != null) {
            for (Map.Entry<String, String> en : incoming.entrySet()) {
                String k = en.getKey();
                if (!required.contains(k)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "渠道 " + channel + " 不支持的配置键: " + k + "（允许: " + String.join(",", required) + "）");
                }
                String v = en.getValue() == null ? "" : en.getValue().trim();
                if (!v.isEmpty()) {
                    merged.put(k, v);
                }
            }
        }
        // 必填全集校验
        List<String> missing = required.stream()
                .filter(k -> merged.getOrDefault(k, "").isBlank())
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "缺少必填配置键: " + String.join(",", missing));
        }

        PaymentChannelConfigEntity e = existing != null ? existing : new PaymentChannelConfigEntity();
        e.setChannel(channel);
        e.setConfigEncrypted(aesEncryptService.encrypt(writeJson(merged)));
        e.setConfigTails(writeJson(maskAll(merged)));
        if (existing == null) {
            configMapper.insert(e);
        } else {
            configMapper.updateById(e);
        }
        log.info("支付渠道配置已更新: channel={}, 键数={}（值不落日志）", channel, merged.size());
    }

    // ---------- 内部桥：SDK 实现落地时渠道类用（DB 优先，env 兜底） ----------

    /** 解密后的渠道配置；DB 无行 → env 兜底组装（可能含空值，调用方判空白）。 */
    public Map<String, String> getDecrypted(String channel) {
        PaymentChannelConfigEntity e = configMapper.selectByChannel(channel);
        if (e != null) {
            return decryptToMap(e.getConfigEncrypted());
        }
        Map<String, String> env = new LinkedHashMap<>();
        if (PaymentOrderEntity.CHANNEL_ALIPAY.equals(channel)) {
            env.put("appId", envAlipayAppId);
            env.put("privateKey", envAlipayPrivateKey);
            env.put("alipayPublicKey", envAlipayPublicKey);
        } else if (PaymentOrderEntity.CHANNEL_WECHAT.equals(channel)) {
            env.put("mchId", envWechatMchId);
            env.put("appId", envWechatAppId);
            env.put("apiV3Key", envWechatApiV3Key);
        }
        return env;
    }

    // ---------- 工具 ----------

    /** 脱敏：长度≤4 全遮（不泄露短值任何字符），否则保留尾 4 位。 */
    static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }

    private Map<String, String> maskAll(Map<String, String> plain) {
        Map<String, String> tails = new LinkedHashMap<>();
        plain.forEach((k, v) -> tails.put(k, mask(v)));
        return tails;
    }

    private Map<String, String> decryptToMap(String encrypted) {
        try {
            return objectMapper.readValue(aesEncryptService.decrypt(encrypted), MAP_TYPE);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "支付渠道配置解密失败（密钥可能被换）");
        }
    }

    private Map<String, String> parseMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String writeJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "支付渠道配置序列化失败");
        }
    }
}
