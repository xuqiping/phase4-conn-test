package com.superprogrammer.chat.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 计划12 · C · raw 批量删除请求（仅删本人，向量 13：ownership 过滤 + 返实际有权条数）。
 */
@Data
public class MemoryRawBatchDeleteRequest {
    @NotEmpty
    private List<Long> ids;
}
