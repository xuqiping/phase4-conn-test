package com.superprogrammer.media.provider;

import com.superprogrammer.media.dto.MediaGenRequest;
import com.superprogrammer.media.dto.MediaGenResult;

/**
 * 媒体生成 provider 抽象（任务型，video + image 通用）。
 *
 * <p>与 {@code LlmProviderInterface}（chat/chatStream/embed 同步/流式）正交：
 * 媒体生成是异步任务协议（create→poll→result），不能塞进 chat provider。
 *
 * <p>抽象留位：SeedDance 视频首实现；后续 SeedDream 生图（SD-11）/对话集成（SD-9）/
 * 工作流节点（SD-10）复用同骨架，仅新增 impl。
 *
 * <p>实现约束：
 * <ul>
 *   <li>{@code createTask} 返回 provider 侧异步任务 id（Ark {@code cct-xxx}）。</li>
 *   <li>{@code queryTask} 返回统一态 {@link MediaGenResult}，屏蔽 provider 原生 status 差异。</li>
 *   <li>密钥不明文落日志；失败原因脱敏截断。</li>
 * </ul>
 */
public interface MediaGenProvider {

    /** provider 标识（如 "ark-seedance"）。 */
    String getId();

    /**
     * 提交生成任务。
     *
     * @return provider 侧异步任务 id（用于后续 queryTask 轮询）
     */
    String createTask(MediaGenRequest request);

    /**
     * 查询任务态。未到终态返 PENDING/RUNNING，worker 据此继续退避轮询。
     */
    MediaGenResult queryTask(String providerTaskId);
}
