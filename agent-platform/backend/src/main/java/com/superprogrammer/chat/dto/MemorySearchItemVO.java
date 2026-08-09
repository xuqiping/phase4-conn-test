package com.superprogrammer.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索候选项（二期 P1 · 项目↔个人授权的用户/项目关键词检索）。
 * <p>
 * 仅返 {@code id + name}（不含任何内容/权限信息），用于「项目授权个人」选被授权人、
 * 「个人申请召回」选目标项目。关键词 LIKE 限 10 条，避免全量枚举。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemorySearchItemVO {
    private Long id;
    private String name;
}
