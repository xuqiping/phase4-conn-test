package com.superprogrammer.canvas.service;

import com.superprogrammer.canvas.dto.CanvasNodeDTO;
import com.superprogrammer.canvas.dto.NodeRunResult;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 画布节点产出触发（plan C4+）。按 {@link CanvasNodeDTO#getType()} 分发生成：
 *
 * <ul>
 *   <li><b>text</b> → {@link LlmGateway#chat}，产出内联进 dataPatch(outputText)，不落 stored_files（文本小）。</li>
 *   <li><b>image</b>(AI 生图) → 待生图 provider（plan R-3 平行子 plan）；MVP 抛固定话术，引导走上传端点。</li>
 *   <li><b>video/audio/script</b> → C5/C6/C7 各自扩展，本类按类型分发的骨架先就位。</li>
 * </ul>
 *
 * <p>无状态：只调 provider + 组装 {@link NodeRunResult}，不读写 snapshot（前端合并 dataPatch 后整存保存）。
 *
 * <p>可观测性：节点运行打日志（nodeId/type/userId/耗时），复用 media traceId 风格。
 * 错误处理：失败固定话术，不透传 {@code e.getMessage()}（plan 安全清单）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasNodeRunnerService {

    /** 节点 prompt 长度上限（plan 安全清单「输入校验」）。 */
    private static final int PROMPT_MAX_LEN = 8000;

    private final LlmGateway llmGateway;

    /** 文本节点默认模型（可被 node.data.model 覆盖；未配 provider 时走全局 doubao）。 */
    @Value("${canvas.text-model:doubao-seed-2.0-code}")
    private String defaultTextModel;

    /**
     * 运行单节点（无状态，不改 snapshot）。
     *
     * @param node   前端传来的节点快照（id/type/data）
     * @param userId 提交用户（LLM 计 user provider override + ownership 透传）
     * @return 运行结果（前端合并 dataPatch 进 node.data）
     */
    public NodeRunResult run(CanvasNodeDTO node, Long userId) {
        if (node == null || node.getType() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "节点类型缺失");
        }
        String type = node.getType();
        log.info("canvas node run: nodeId={} type={} userId={}", node.getId(), type, userId);
        switch (type) {
            case CanvasNodeDTO.TYPE_TEXT:
                return runText(node, userId);
            case CanvasNodeDTO.TYPE_IMAGE:
                return runImage(node, userId);
            case CanvasNodeDTO.TYPE_VIDEO:
            case CanvasNodeDTO.TYPE_AUDIO:
            case CanvasNodeDTO.TYPE_SCRIPT:
                // C5/C6/C7 各自接入；在此之前给明确话术，避免前端误以为可跑
                throw new BusinessException(ErrorCode.UNPROCESSABLE,
                        type + " 节点生成尚未接入（对应 C5/C6/C7），敬请期待");
            default:
                throw new BusinessException(ErrorCode.BAD_REQUEST, "未知节点类型: " + type);
        }
    }

    /** 文本节点 → LlmGateway.chat → outputText。 */
    private NodeRunResult runText(CanvasNodeDTO node, Long userId) {
        String prompt = readString(node, "prompt");
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        if (prompt.length() > PROMPT_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词长度超限（≤" + PROMPT_MAX_LEN + "）");
        }
        String model = readString(node, "model");
        if (model == null || model.isBlank()) {
            model = defaultTextModel;
        }

        long started = System.currentTimeMillis();
        try {
            LlmRequest req = LlmRequest.builder()
                    .model(model)
                    .messages(List.of(new LlmMessage("user", prompt)))
                    .stream(false)
                    .build();
            LlmResponse resp = userId == null ? llmGateway.chat(req) : llmGateway.chat(req, userId);
            String output = resp == null ? "" : (resp.getContent() == null ? "" : resp.getContent());
            long cost = System.currentTimeMillis() - started;
            log.info("canvas text node done: nodeId={} model={} costMs={} outLen={}",
                    node.getId(), model, cost, output.length());

            return NodeRunResult.builder()
                    .nodeId(node.getId())
                    .status("success")
                    .dataPatch(Map.of(
                            "status", "success",
                            "outputText", output,
                            "model", model,
                            "errorMsg", ""))
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("canvas text node failed: nodeId={} err={}", node.getId(), e.getMessage());
            return NodeRunResult.builder()
                    .nodeId(node.getId())
                    .status("failed")
                    .errorMsg("文本生成失败，请稍后重试")
                    .dataPatch(Map.of("status", "failed", "errorMsg", "文本生成失败，请稍后重试"))
                    .build();
        }
    }

    /**
     * 图片节点 AI 生图。平台现无生图 provider（plan R-3），MVP 引导走上传端点。
     * 生图 provider 落地后在此接 {@code MediaGenProvider} 新 impl（Seedream/Nano-Banana/可灵图）。
     */
    private NodeRunResult runImage(CanvasNodeDTO node, Long userId) {
        throw new BusinessException(ErrorCode.UNPROCESSABLE,
                "图片 AI 生图 provider 尚未配置（R-3 子 plan），请先使用「上传」添加图片");
    }

    private String readString(CanvasNodeDTO node, String key) {
        if (node.getData() == null) {
            return null;
        }
        Object v = node.getData().get(key);
        return v == null ? null : String.valueOf(v);
    }
}
