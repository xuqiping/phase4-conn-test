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
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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
            case CanvasNodeDTO.TYPE_SCRIPT:
                return runScript(node, userId);
            case CanvasNodeDTO.TYPE_AUDIO:
                // C6：上传已走 /upload 端点；TTS/音乐生成待专用 provider（doubao TTS / 音乐模型）
                throw new BusinessException(ErrorCode.UNPROCESSABLE,
                        "音频 TTS/音乐生成 provider 尚未接入（C6），请使用「上传」添加音频");
            case CanvasNodeDTO.TYPE_VIDEO:
                // C5：视频走前端直连 media API（media:gen gated），runner 不重复入口
                throw new BusinessException(ErrorCode.UNPROCESSABLE,
                        "视频节点请通过属性面板「提交视频生成」按钮（走 media API）");
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
     * 生图 provider 落地后在此接 {@code MediaGenProvider} 新 impl（Seedream/Nano-Banana/可铃图）。
     */
    private NodeRunResult runImage(CanvasNodeDTO node, Long userId) {
        throw new BusinessException(ErrorCode.UNPROCESSABLE,
                "图片 AI 生图 provider 尚未配置（R-3 子 plan），请先使用「上传」添加图片");
    }

    /**
     * 脚本节点 → LlmGateway 拆分镜（plan IC-6 / C7）。
     *
     * <p>剧本 → LLM 返回 JSON 数组 {@code [{"index":1,"description":"…"}, …]}；解析失败兜底为单条原始剧本。
     * 批量分镜图生成（R-7 限流）待生图 provider（R-3），本步只拆文本分镜。
     */
    private NodeRunResult runScript(CanvasNodeDTO node, Long userId) {
        String synopsis = readString(node, "synopsis");
        if (synopsis == null || synopsis.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "剧本不能为空");
        }
        if (synopsis.length() > PROMPT_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "剧本长度超限（≤" + PROMPT_MAX_LEN + "）");
        }
        String model = readString(node, "model");
        if (model == null || model.isBlank()) {
            model = defaultTextModel;
        }

        String instruction = """
                你是影视分镜师。把下面剧本拆成 3-10 个分镜，严格只输出 JSON 数组，不要任何解释或 markdown 代码块：
                [{"index":1,"description":"分镜画面描述"},{"index":2,"description":"…"}]
                剧本：
                """.replace("\n", " ") + synopsis;
        long started = System.currentTimeMillis();
        try {
            LlmRequest req = LlmRequest.builder()
                    .model(model)
                    .messages(List.of(new LlmMessage("user", instruction)))
                    .temperature(0.5)
                    .stream(false)
                    .build();
            LlmResponse resp = userId == null ? llmGateway.chat(req) : llmGateway.chat(req, userId);
            String content = resp == null ? "" : (resp.getContent() == null ? "" : resp.getContent());
            List<Map<String, Object>> scenes = parseScenes(content);
            long cost = System.currentTimeMillis() - started;
            log.info("canvas script breakdown done: nodeId={} model={} costMs={} scenes={}",
                    node.getId(), model, cost, scenes.size());

            Map<String, Object> patch = new java.util.HashMap<>();
            patch.put("status", "success");
            patch.put("scenes", scenes);
            patch.put("model", model);
            patch.put("errorMsg", "");
            return NodeRunResult.builder()
                    .nodeId(node.getId())
                    .status("success")
                    .dataPatch(patch)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("canvas script breakdown failed: nodeId={} err={}", node.getId(), e.getMessage());
            Map<String, Object> patch = new java.util.HashMap<>();
            patch.put("status", "failed");
            patch.put("errorMsg", "分镜失败，请稍后重试");
            return NodeRunResult.builder()
                    .nodeId(node.getId())
                    .status("failed")
                    .errorMsg("分镜失败，请稍后重试")
                    .dataPatch(patch)
                    .build();
        }
    }

    /**
     * 从 LLM 输出解析分镜数组。容错：剥 ```json 代码块围栏 → 取首个 {@code [..]} 子串 → 解析；
     * 全失败兜底单条 {@code [{index:1, description: 原文}]}（不阻断，保底可用）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseScenes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String cleaned = raw.trim();
        // 剥 markdown 代码块围栏
        if (cleaned.startsWith("```")) {
            int nl = cleaned.indexOf('\n');
            if (nl > 0) cleaned = cleaned.substring(nl + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.trim();
        }
        // 取首个 JSON 数组片段
        int lb = cleaned.indexOf('[');
        int rb = cleaned.lastIndexOf(']');
        if (lb >= 0 && rb > lb) {
            try {
                return objectMapper.readValue(cleaned.substring(lb, rb + 1),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                log.warn("分镜 JSON 解析失败，走兜底: {}", e.getMessage());
            }
        }
        // 兜底：原文作单分镜
        Map<String, Object> fallback = new java.util.HashMap<>();
        fallback.put("index", 1);
        fallback.put("description", cleaned);
        return List.of(fallback);
    }

    private String readString(CanvasNodeDTO node, String key) {
        if (node.getData() == null) {
            return null;
        }
        Object v = node.getData().get(key);
        return v == null ? null : String.valueOf(v);
    }
}
