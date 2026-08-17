package com.superprogrammer.chat.dto;

import lombok.Data;

/**
 * 5x 四轮 C6：文件「被哪个项目收录」批量查询行。
 * <p>
 * 一个文件可能被多个项目收录（多项目各一条 FILE 条目；同项目去重由 countFileEntry 保证），
 * service 层按 {@code fileId} 分组、对 {@code projectId} 去重后拼「收录于：项目A、项目B」徽标。
 * 仅返回用户可访问域（ACTIVE 成员）内项目——非成员/已离职项目不透名（信息最小化）。
 */
@Data
public class FileProjectRefRow {
    private String fileId;
    private Long projectId;
    private String projectName;
}
