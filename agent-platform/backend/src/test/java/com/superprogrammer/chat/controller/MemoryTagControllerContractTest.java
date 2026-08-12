package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryTagEditRequest;
import com.superprogrammer.chat.dto.MemoryTagVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 计划12 B · 标签库契约断言（无 merge/split/re-extract 端点 + VO 不外露敏感字段）。
 * <p>
 * L12 边界：误并不可逆 → 控制器刻意不暴露任何归并/拆分/重抽端点；
 * 向量 4：VO 只露 label + subject + topic + usage_count（+ id 寻址），aliases/anchor 不外露。
 * <p>
 * 反射断言——API 演进时若有人不慎加了 merge/split 端点或 VO 加了敏感字段，此测必红。
 */
class MemoryTagControllerContractTest {

    /** 控制器不得出现 merge / split / re-extract 语义端点（误并不可逆）。 */
    @Test
    void noMergeSplitReExtractEndpoints() {
        Set<String> methodNames = Arrays.stream(MemoryTagController.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        for (String name : methodNames) {
            String lower = name.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("merge"), () -> "禁止 merge 端点（误并不可逆）: " + name);
            assertFalse(lower.contains("split"), () -> "禁止 split 端点（误拆不可逆）: " + name);
            assertFalse(lower.contains("reextract") || lower.contains("reextracttag")
                            || lower.contains("reextract") || lower.contains("retag"),
                    () -> "禁止 re-extract/重抽 端点（保护已生成 summary 的 tag_id）: " + name);
        }
        // 至少有 list + edit + create 三个端点（契约存在性；P3a 新增主动建标签 create）
        assertTrue(methodNames.contains("list"), "应有 list 端点");
        assertTrue(methodNames.contains("edit"), "应有 edit 端点");
        assertTrue(methodNames.contains("create"), "应有 create 端点（P3a 主动建标签）");
    }

    /** VO 字段集 = {id, subject, topic, label, usageCount}——无 aliases / anchor。 */
    @Test
    void voDoesNotLeakAliasesOrAnchor() {
        Set<String> fields = Arrays.stream(MemoryTagVO.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertFalse(fields.contains("aliases"), "VO 不得外露 aliases（向量 4）");
        assertFalse(fields.stream().anyMatch(f -> f.toLowerCase(Locale.ROOT).contains("anchor")),
                "VO 不得外露 anchor_* 字段（向量 4）");
        assertTrue(fields.contains("label") && fields.contains("subject")
                        && fields.contains("topic") && fields.contains("usageCount"),
                "VO 应露 label/subject/topic/usageCount");
    }

    /** 编辑请求体不含 merge/split/with 指定目标字段——只有改 label / 补 aliases 两动作。 */
    @Test
    void editRequestHasNoMergeSplitFields() {
        Set<String> fields = Arrays.stream(MemoryTagEditRequest.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());
        for (String f : fields) {
            String lower = f.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("merge") || lower.contains("split") || lower.contains("targetid")
                            || lower.contains("reextract"),
                    () -> "编辑请求不得含归并/拆分/重抽字段: " + f);
        }
    }
}
