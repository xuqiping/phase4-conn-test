package com.superprogrammer.billing.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 价表批量导入结果（7x-2）。
 * <p>非法行不中断整体导入，记入 errors；前端据此提示「新增 N / 更新 M / 失败 K」。
 * 与 {@link ProviderImportResult} 镜像。
 */
@Data
@Builder
public class PricingImportResult {
    private int created;
    private int updated;
    private int failed;
    /** 失败行的原因摘要（如「第3行 providerId 不存在」「第5行 kind 与供应商类别不匹配」） */
    @Builder.Default
    private List<String> errors = new ArrayList<>();

    public void incCreated() { created++; }
    public void incUpdated() { updated++; }
    public void incFailed(String reason) {
        failed++;
        errors.add(reason);
    }
}
