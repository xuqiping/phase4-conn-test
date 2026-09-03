package com.superprogrammer.knowledge.attachment;

import com.superprogrammer.knowledge.config.RagRecallProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * C2 附件图片识图结果 Redis 缓存（WP1 Step6）。
 *
 * <p>图片附件不预提取（上传零识图成本），检索命中时实时 VLM；结果落 Redis 供下次直取。
 * key=sha256(fileRef|visionModel|promptVersion)——同图同模型同提示词版本才命中；
 * TTL（默认 30d）兜底清理：文档删除/换版后旧文案自然过期，无主动失效链路
 * （换版 fileRef 变 → key 变，天然隔离）。
 *
 * <p>Redis 任何故障一律按 miss 处理（get 返 null / put 静默丢弃）并 warn 留痕——
 * 缓存只是省钱省时通道，不能成为检索主链的单点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentVisionCache {

    private static final String KEY_PREFIX = "rag:attach:vision:";

    private final StringRedisTemplate redisTemplate;
    private final RagRecallProperties recallProps;

    /** 命中返回识图文本；未命中/Redis 故障返回 null（调用方走实时 VLM）。 */
    public String get(String key) {
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + key);
        } catch (Exception e) {
            log.warn("附件识图缓存读失败（按 miss 处理）key={} err={}", key, e.getMessage());
            return null;
        }
    }

    /** 写缓存（TTL 天，超时自动过期）。失败静默——仅损失下次命中率。 */
    public void put(String key, String text) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + key, text,
                    Duration.ofDays(recallProps.getAttachment().getVisionCacheTtlDays()));
        } catch (Exception e) {
            log.warn("附件识图缓存写失败（忽略）key={} err={}", key, e.getMessage());
        }
    }
}
