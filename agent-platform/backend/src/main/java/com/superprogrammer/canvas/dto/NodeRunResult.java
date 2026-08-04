package com.superprogrammer.canvas.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 单节点运行结果（C4+ 节点产出触发）。
 *
 * <p>设计为**无状态回包**：Controller 不直接改 snapshot，只回「该节点本次产出的数据补丁」，
 * 由前端合并进 {@code node.data} 后随快照整存保存（同画布编辑既有范式）。
 * 这样后端 Runner 职责单一（只调 provider + 组装结果），不碰 JSONB 读写，零 snapshot 竞态。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@link #status} — idle/running/success/failed，写回 {@code node.data.status}（前端联动徽标）。</li>
 *   <li>{@link #dataPatch} — 合并进 {@code node.data} 的补丁（如 outputText/fileId/previewUrl/errorMsg）。</li>
 *   <li>{@link #outputs} — 产出物引用（落 stored_files 的 image/video/audio）；文本节点产出小，内联进 dataPatch 不走此。</li>
 *   <li>{@link #errorMsg} — 失败固定话术（不透传 e.getMessage()，plan 安全清单「错误处理」）。</li>
 * </ul>
 */
@Data
@Builder
public class NodeRunResult {

    /** 节点 id（回显）。 */
    private String nodeId;
    /** 运行态。 */
    private String status;
    /** 合并进 node.data 的补丁（前端 Object.assign(node.data, dataPatch)）。 */
    private Map<String, Object> dataPatch;
    /** 产出物引用（image/video/audio 走 stored_files 时填）。 */
    private List<NodeOutputRef> outputs;
    /** 失败话术（成功为 null）。 */
    private String errorMsg;
}
