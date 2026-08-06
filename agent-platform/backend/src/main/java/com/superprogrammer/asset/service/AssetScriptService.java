package com.superprogrammer.asset.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superprogrammer.asset.dto.SceneVO;
import com.superprogrammer.asset.dto.ScriptBreakdownRequest;
import com.superprogrammer.asset.dto.ScriptBreakdownVO;
import com.superprogrammer.asset.dto.VersionCreateRequest;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.mapper.AssetMapper;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 项目资产库·剧本 AI 拆分场（plan §S6 / FR-010，设计方案 §四 剧本）。
 *
 * <p>剧本资产「拆分场」端点 → {@link LlmGateway} → scenes JSONB 入 {@code content.scenes} 并产新版本。
 *
 * <p>容错复用 canvas parseScenes 模式（剥围栏 → 取 {@code [..]} → 兜底单条原文），不阻断保底可用。
 * 复用 LlmGateway 现有超时 + 容错话术（运维清单）。
 *
 * <p>权限：requireWrite（写 content.scenes=版本事件）。
 *
 * <p>可观测性：拆分场打日志（assetId/model/场数/耗时）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetScriptService {

    private static final int SCRIPT_MAX_LEN = 8000;

    private final AssetMapper assetMapper;
    private final AssetAclService aclService;
    private final AssetVersionService versionService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    @Value("${asset.script-model:doubao-seed-2.0-code}")
    private String defaultModel;

    /**
     * 拆分场。
     *
     * @throws BusinessException 非剧本类型 / 正文空 / 超 8000 / LLM 失败
     */
    @Transactional
    public ScriptBreakdownVO breakdown(Long assetId, Long userId, boolean admin, ScriptBreakdownRequest req) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        aclService.requireWrite(asset.getProjectId(), userId, admin);
        if (!Asset.MEDIA_SCRIPT.equals(asset.getMediaType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅剧本资产可拆分场");
        }
        String body = readScriptBody(asset.getContent());
        if (body == null || body.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "剧本正文不能为空");
        }
        if (body.length() > SCRIPT_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "剧本长度超限（≤" + SCRIPT_MAX_LEN + "）");
        }
        String model = (req != null && req.getModel() != null && !req.getModel().isBlank())
                ? req.getModel() : defaultModel;

        String instruction = """
                你是影视分镜师。把下面剧本拆成 3-10 个分场，严格只输出 JSON 数组，不要任何解释或 markdown 代码块：
                [{"index":1,"description":"场景/画面描述"},{"index":2,"description":"…"}]
                剧本：
                """.replace("\n", " ") + body;

        long started = System.currentTimeMillis();
        List<SceneVO> scenes;
        try {
            LlmRequest llmReq = LlmRequest.builder()
                    .model(model)
                    .messages(List.of(new LlmMessage("user", instruction)))
                    .temperature(0.5)
                    .stream(false)
                    .build();
            LlmResponse resp = userId == null ? llmGateway.chat(llmReq) : llmGateway.chat(llmReq, userId);
            String content = resp == null ? "" : (resp.getContent() == null ? "" : resp.getContent());
            scenes = parseScenes(content);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("script breakdown failed: assetId={} err={}", assetId, e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "分场失败，请稍后重试");
        }
        long cost = System.currentTimeMillis() - started;
        log.info("script breakdown done: assetId={} model={} costMs={} scenes={}", assetId, model, cost, scenes.size());

        // 写入 content.scenes（保留 body），产新版本
        String merged = mergeScenes(asset.getContent(), scenes, model);
        VersionCreateRequest vReq = new VersionCreateRequest();
        vReq.setContent(merged);
        vReq.setChangeNote("AI 拆分场（" + scenes.size() + " 场）");
        int newVer = versionService.createVersion(assetId, userId, admin, vReq);

        return ScriptBreakdownVO.builder()
                .scenes(scenes)
                .model(model)
                .version(newVer)
                .build();
    }

    /** 读剧本正文：synopsis 优先（规范键，与 AssetCanvasBridgeService.extractTextContent / 前端新建剧本一致），
     *  回退 body（旧态/历史数据），再回退纯文本（最旧态）。 */
    private String readScriptBody(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(rawContent);
            if (root.has("synopsis") && !root.get("synopsis").asText("").isBlank()) {
                return root.get("synopsis").asText("");
            }
            if (root.has("body")) {
                return root.get("body").asText("");
            }
            return null;
        } catch (Exception e) {
            // 旧态非 JSON：当作正文
            return rawContent;
        }
    }

    /** 合并分场进 content（保留 synopsis/body 等既有键）。 */
    String mergeScenes(String rawContent, List<SceneVO> scenes, String model) {
        try {
            ObjectNode root = (rawContent == null || rawContent.isBlank())
                    ? objectMapper.createObjectNode()
                    : (ObjectNode) objectMapper.readTree(rawContent);
            root.set("scenes", objectMapper.valueToTree(scenes));
            root.put("breakdownModel", model);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("merge scenes failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分场合并失败：content 非合法 JSON");
        }
    }

    /**
     * 解析 LLM 分场输出。容错：剥 ```json 围栏 → 取首个 {@code [..]} → 解析；
     * 全失败兜底单条 {@code [{index:1, description: 原文}]}（复用 canvas parseScenes 模式）。
     */
    private List<SceneVO> parseScenes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int nl = cleaned.indexOf('\n');
            if (nl > 0) cleaned = cleaned.substring(nl + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.trim();
        }
        int lb = cleaned.indexOf('[');
        int rb = cleaned.lastIndexOf(']');
        if (lb >= 0 && rb > lb) {
            try {
                List<Map<String, Object>> list = objectMapper.readValue(cleaned.substring(lb, rb + 1),
                        new TypeReference<List<Map<String, Object>>>() {});
                List<SceneVO> scenes = new ArrayList<>();
                int idx = 1;
                for (Map<String, Object> m : list) {
                    Object desc = m.get("description");
                    if (desc == null) desc = m.get("scene");
                    scenes.add(new SceneVO(
                            m.get("index") instanceof Number n ? n.intValue() : idx,
                            desc == null ? "" : String.valueOf(desc)));
                    idx++;
                }
                return scenes;
            } catch (Exception e) {
                log.warn("分场 JSON 解析失败，走兜底: {}", e.getMessage());
            }
        }
        // 兜底：原文作单分场
        return List.of(new SceneVO(1, cleaned));
    }
}
