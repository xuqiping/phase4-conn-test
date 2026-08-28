package com.superprogrammer.media.provider;

import com.superprogrammer.media.dto.MediaGenRequest;
import com.superprogrammer.media.dto.MediaGenResult;
import com.superprogrammer.media.dto.PreparedMediaRequest;

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

    /**
     * provider 协议标识（= llm_providers.protocol 的取值域，如 "ark"）。
     * 视频模型接入扩展 MVR-1：worker 按 provider 行 protocol 路由到本适配器 bean。
     */
    String getId();

    /**
     * 提交生成任务。
     *
     * @return provider 侧异步任务 id（用于后续 queryTask 轮询）
     */
    String createTask(MediaGenRequest request);

    /**
     * 发送前只构建一次实际 body + 派生审计快照（不含密钥/data URI）。
     * MVR-1：上位为接口方法——快照落库时机由 worker 统一控制，多协议适配器同口径。
     */
    PreparedMediaRequest prepareCreateRequest(MediaGenRequest request);

    /** 使用已准备的同一个 body 发请求，避免保存快照后又重新推导请求。 */
    String createPreparedTask(MediaGenRequest request, PreparedMediaRequest prepared);

    /**
     * 查询任务态。未到终态返 PENDING/RUNNING，worker 据此继续退避轮询。
     * MVR-1：providerId 上位为接口参数——多 provider 行并存时各持各的密钥/端点，
     * 查态必须按任务落库的 providerId 走对应行；为空由实现自行回退默认 provider。
     */
    MediaGenResult queryTask(String providerTaskId, Long providerId);

    /** 单参便捷版（默认 provider），测试/旧调用方兼容。 */
    default MediaGenResult queryTask(String providerTaskId) {
        return queryTask(providerTaskId, null);
    }

    /**
     * VIDEO provider 连通性测试（供应商管理页「测试」按钮，category=VIDEO 分流）。
     * RE/MVR-5：测试入口按 provider 行 protocol 路由（与 worker 同口径），不再写死 ark。
     * 任务型适配器覆写为各自零成本探测（GET 查态端点/不存在id）；未覆写给明确话术。
     */
    default com.superprogrammer.llm.dto.TestConnectionResult testConnection(Long providerId) {
        return com.superprogrammer.llm.dto.TestConnectionResult.fail("该协议未接入连通性测试");
    }
}
