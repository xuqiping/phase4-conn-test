package com.superprogrammer.llm.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 供应商批量导入结果（问题 10x-2）。
 * <p>非法行不中断整体导入，记入 errors；前端据此提示「新增 N / 更新 M / 失败 K」。
 */
@Data
@Builder
public class ProviderImportResult {
    private int created;
    private int updated;
    private int failed;
    /** 失败行的原因摘要（如「第3行 name 为空」「第5行 apiEndpoint 非法 URL」） */
    @Builder.Default
    private List<String> errors = new ArrayList<>();

    public void incCreated() { created++; }
    public void incUpdated() { updated++; }
    public void incFailed(String reason) {
        failed++;
        errors.add(reason);
    }
}
