package com.superprogrammer.asset.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superprogrammer.asset.dto.SceneVO;
import com.superprogrammer.asset.dto.ScriptBreakdownRequest;
import com.superprogrammer.asset.dto.ScriptBreakdownVO;
import com.superprogrammer.asset.dto.StoryboardBreakdownRequest;
import com.superprogrammer.asset.dto.StoryboardBreakdownVO;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** 拆镜模板上限（S19 安全清单·输入校验）。 */
    private static final int TEMPLATE_MAX_LEN = 2000;
    /** 图片目录注入上限（防 prompt token 爆，运维清单）。 */
    private static final int CATALOG_LIMIT = 50;

    private final AssetMapper assetMapper;
    private final AssetAclService aclService;
    private final AssetVersionService versionService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final AssetService assetService;
    private final AssetProjectService assetProjectService;

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
        String model = req == null ? null : req.getModel();

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
            model = llmReq.getModel();
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

    /**
     * 一键分镜（plan §S19 / 1_8.6计划 第 11 点）。
     *
     * <p>对剧本调 LLM 拆镜头 → 每镜头建一个分镜资产（字段 1/2 自动填，含实体→@资产首轮匹配）。
     * 整方法 {@code @Transactional}：任一镜头建库失败全回滚（不留半成品，L13 边界）。
     *
     * <p>安全/运维：循环外 requireWrite 一次；vocab 缺分镜自动补；图片目录 ≤50 注入防 token 爆；
     * LLM 失败/解析失败返固定话术不透传 {@code e.getMessage()}；非法/越项目 assetId 置 null 存痕迹（L16）。
     *
     * @throws BusinessException 非剧本 / 正文空 / 超 8000 / 模板超 2000 / LLM 失败
     */
    @Transactional
    public StoryboardBreakdownVO breakdownStoryboard(Long scriptAssetId, Long userId, boolean admin,
                                                     StoryboardBreakdownRequest req) {
        Asset asset = assetMapper.selectById(scriptAssetId);
        if (asset == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        aclService.requireWrite(asset.getProjectId(), userId, admin);
        if (!Asset.MEDIA_SCRIPT.equals(asset.getMediaType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅剧本资产可一键分镜");
        }
        String body = readScriptBody(asset.getContent());
        if (body == null || body.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "剧本正文不能为空");
        }
        if (body.length() > SCRIPT_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "剧本长度超限（≤" + SCRIPT_MAX_LEN + "）");
        }
        String template = readTemplate(asset.getContent());
        if (template.length() > TEMPLATE_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "拆解模板超限（≤" + TEMPLATE_MAX_LEN + "）");
        }
        String model = req == null ? null : req.getModel();

        // ① 确保 vocab 含分镜（自定义 vocab 删分镜后自动补，L13 边界）
        assetProjectService.ensureMediaType(asset.getProjectId(), Asset.MEDIA_STORYBOARD, Asset.CATEGORY_TEXT);
        // ② 图片目录注入（空目录不阻断，entityRefs 全 null）
        List<AssetService.ImageCatalogItem> catalog = assetService.getImageCatalog(asset.getProjectId(), CATALOG_LIMIT);
        Map<Long, AssetService.ImageCatalogItem> catalogMap = new LinkedHashMap<>();
        Set<Long> catalogIds = new HashSet<>();
        for (AssetService.ImageCatalogItem c : catalog) {
            catalogMap.put(c.id(), c);
            catalogIds.add(c.id());
        }

        String instruction = buildStoryboardPrompt(body, template, catalog);

        long started = System.currentTimeMillis();
        List<ParsedShot> shots;
        try {
            LlmRequest llmReq = LlmRequest.builder()
                    .model(model)
                    .messages(List.of(new LlmMessage("user", instruction)))
                    .temperature(0.5)
                    .stream(false)
                    .build();
            LlmResponse resp = userId == null ? llmGateway.chat(llmReq) : llmGateway.chat(llmReq, userId);
            model = llmReq.getModel();
            String content = resp == null ? "" : (resp.getContent() == null ? "" : resp.getContent());
            shots = parseShots(content, body);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("storyboard breakdown failed: scriptAssetId={} err={}", scriptAssetId, e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "分镜失败，请稍后重试");
        }
        long cost = System.currentTimeMillis() - started;

        // ③ 逐镜建分镜资产（非法/越项目 assetId 置 null 存痕迹，name/mediaType 取自目录非模型，L16）
        List<Long> createdIds = new ArrayList<>();
        int idx = 1;
        for (ParsedShot shot : shots) {
            ArrayNode entityRefs = objectMapper.createArrayNode();
            if (shot.entities != null) {
                for (ParsedEntity e : shot.entities) {
                    ObjectNode ref = objectMapper.createObjectNode();
                    ref.put("key", e.key == null ? "" : e.key);
                    Long assetId = (e.assetId != null && catalogIds.contains(e.assetId)) ? e.assetId : null;
                    if (assetId != null) {
                        ref.put("assetId", assetId);
                    } else {
                        ref.putNull("assetId");
                    }
                    if (assetId != null) {
                        AssetService.ImageCatalogItem m = catalogMap.get(assetId);
                        ref.put("name", m.name());
                        ref.put("mediaType", m.mediaType());
                    }
                    entityRefs.add(ref);
                }
            }
            String shotContent = buildShotContent(shot.index > 0 ? shot.index : idx, scriptAssetId, shot.prompt, entityRefs);
            Asset created = assetService.internalCreateText(asset.getProjectId(), Asset.MEDIA_STORYBOARD,
                    asset.getName() + " · 镜头" + (shot.index > 0 ? shot.index : idx),
                    null, shotContent, List.of(), userId);
            createdIds.add(created.getId());
            idx++;
        }

        // ④ 剧本 content 记 meta（storyboardModel/storyboardAt），产新版本（不存 shots）
        String merged = mergeStoryboardMeta(asset.getContent(), model, createdIds.size());
        VersionCreateRequest vReq = new VersionCreateRequest();
        vReq.setContent(merged);
        vReq.setChangeNote("AI 一键分镜（" + createdIds.size() + " 镜）");
        int newVer = versionService.createVersion(scriptAssetId, userId, admin, vReq);

        log.info("storyboard breakdown done: scriptAssetId={} model={} costMs={} shots={} createdAssetIds={}",
                scriptAssetId, model, cost, createdIds.size(), createdIds);

        return StoryboardBreakdownVO.builder()
                .count(createdIds.size())
                .createdAssetIds(createdIds)
                .model(model)
                .version(newVer)
                .build();
    }

    /** 读拆解模板（content.template，用户编辑器输入），缺省空串。 */
    private String readTemplate(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(rawContent);
            return root.has("template") ? root.get("template").asText("") : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 拼分镜师 prompt：角色 + 模板指导 + 图片目录（紧凑 JSON）+ 剧本 → 输出镜头数组。 */
    String buildStoryboardPrompt(String body, String template, List<AssetService.ImageCatalogItem> catalog) {
        StringBuilder catalogJson = new StringBuilder("[");
        for (int i = 0; i < catalog.size(); i++) {
            AssetService.ImageCatalogItem c = catalog.get(i);
            if (i > 0) {
                catalogJson.append(",");
            }
            catalogJson.append("{\"id\":").append(c.id())
                    .append(",\"name\":\"").append(escape(c.name())).append("\"")
                    .append(",\"role\":\"").append(c.roleKeys() == null ? "" : escape(String.join("/", c.roleKeys())))
                    .append("\"}");
        }
        catalogJson.append("]");
        String tplGuide = template.isBlank()
                ? "（用户未设拆解规范，按通用影视分镜规范：每镜含景别/主体动作/氛围）"
                : "（须遵循以下拆解规范）：" + template;
        return "你是影视分镜师。把下面剧本拆成 3-30 个镜头，严格只输出 JSON 数组，不要任何解释或 markdown 代码块："
                + "[{\"index\":1,\"prompt\":\"该镜头画面描述\",\"entities\":[{\"key\":\"实体名（如主角）\",\"assetId\":1}]}]"
                + " 其中 assetId 只能从下方图片资产目录的 id 中选（无匹配填 null），entities 可空数组。"
                + tplGuide
                + " 图片资产目录：" + catalogJson
                + " 剧本：" + body;
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 构造单镜头分镜资产 content（5 字段 schema：1/2/4 全功能，3/5 占位 none）。 */
    String buildShotContent(int shotIndex, Long parentId, String prompt, ArrayNode entityRefs) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("shotIndex", shotIndex);
        if (parentId != null) {
            root.put("parentId", parentId);
        }
        root.put("prompt", prompt == null ? "" : prompt);
        root.set("entityRefs", entityRefs);
        ObjectNode imageGen = objectMapper.createObjectNode();
        imageGen.put("status", "none"); // 字段3 占位（待图模型 R-3）
        root.set("imageGen", imageGen);
        ObjectNode videoInputs = objectMapper.createObjectNode();
        videoInputs.set("audioRefs", objectMapper.createArrayNode());
        videoInputs.set("imageRefs", objectMapper.createArrayNode());
        root.set("videoInputs", videoInputs);
        ObjectNode videoGen = objectMapper.createObjectNode();
        videoGen.put("status", "none"); // 字段5 占位
        root.set("videoGen", videoGen);
        return root.toString();
    }

    /** 剧本 content 追加 storyboardModel/storyboardAt/shotCount（保留既有键，不存 shots）。 */
    String mergeStoryboardMeta(String rawContent, String model, int shotCount) {
        try {
            ObjectNode root = (rawContent == null || rawContent.isBlank())
                    ? objectMapper.createObjectNode()
                    : (ObjectNode) objectMapper.readTree(rawContent);
            root.put("storyboardModel", model);
            root.put("storyboardAt", System.currentTimeMillis());
            root.put("shotCount", shotCount);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("merge storyboard meta failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分镜 meta 合并失败：content 非合法 JSON");
        }
    }

    /** 解析 LLM 分镜输出。容错同 parseScenes：剥围栏→取 {@code [..]}→解析；全失败兜底单镜（原文作 prompt）。 */
    @SuppressWarnings("unchecked")
    List<ParsedShot> parseShots(String raw, String fallbackBody) {
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
                List<ParsedShot> shots = new ArrayList<>();
                int idx = 1;
                for (Map<String, Object> m : list) {
                    Object promptObj = m.get("prompt");
                    if (promptObj == null) promptObj = m.get("description");
                    int index = m.get("index") instanceof Number n ? n.intValue() : idx;
                    List<ParsedEntity> entities = new ArrayList<>();
                    Object ents = m.get("entities");
                    if (ents instanceof List<?> entList) {
                        for (Object eo : entList) {
                            if (eo instanceof Map<?, ?> em) {
                                String key = em.get("key") == null ? "" : String.valueOf(em.get("key"));
                                Long assetId = parseLongObj(em.get("assetId"));
                                entities.add(new ParsedEntity(key, assetId));
                            }
                        }
                    }
                    shots.add(new ParsedShot(index, promptObj == null ? "" : String.valueOf(promptObj), entities));
                    idx++;
                }
                return shots;
            } catch (Exception e) {
                log.warn("分镜 JSON 解析失败，走兜底: {}", e.getMessage());
            }
        }
        // 兜底：原文作单镜
        return List.of(new ParsedShot(1, fallbackBody == null ? "" : fallbackBody, List.of()));
    }

    private Long parseLongObj(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析中间结构（LLM 输出单镜）。 */
    record ParsedShot(int index, String prompt, List<ParsedEntity> entities) {
    }

    record ParsedEntity(String key, Long assetId) {
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
