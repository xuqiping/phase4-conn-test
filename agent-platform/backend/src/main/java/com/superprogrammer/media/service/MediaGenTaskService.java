package com.superprogrammer.media.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.asset.service.AssetService;
import com.superprogrammer.billing.service.InflightGateService;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.billing.service.PointsWalletService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.config.MediaModelCapability;
import com.superprogrammer.media.config.ImageModelCapability;
import com.superprogrammer.media.config.MediaModelCapabilityService;
import com.superprogrammer.media.dto.AttachmentRef;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.media.service.internal.MediaInflightGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 媒体生成任务提交入口。
 *
 * <p>职责：参数校验（运维上限 + 模型能力上限）+ 解析视频 provider/model
 * （指定 model 时跨全部 ACTIVE VIDEO provider 反查；未指定回退 media.provider-name 的 models[0]）
 * + 建 PENDING 任务行 + 返回 taskId。
 * 不在此派发执行——交由 {@link com.superprogrammer.media.service.MediaGenTaskWorker} 定时轮询认领
 * （纯 poll 模式，照抄 IndexJob：天然崩溃恢复，重启后下次 poll 自动续跑 RUNNING 行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaGenTaskService {

    /** 分辨率等级（≤ maxRes 运维上限校验用）。 */
    private static final Map<String, Integer> RES_RANK = Map.of("480p", 1, "720p", 2, "1080p", 3, "4K", 4);
    /** 附件类型白名单。 */
    private static final Set<String> ATTACHMENT_KINDS = Set.of("image", "video", "audio");
    /** 提示词长度上限（对齐画布/资产 8000；原 2000 过短）。 */
    private static final int PROMPT_MAX_LEN = 8000;
    /** HHX-9：Context-IR 提交期估算输出 token（增强提示词典型 3-4k，取上限档；结算按实）。 */
    private static final int CONTEXT_IR_EST_OUT_TOKENS = 4000;

    private final MediaGenTaskMapper taskMapper;
    private final MediaModelService mediaModelService;
    private final MediaModelCapabilityService capabilityService;
    private final FileStorageService fileStorageService;
    private final MediaGenProperties properties;
    private final ObjectMapper objectMapper;
    private final AssetService assetService;
    private final PointsWalletService walletService;
    /** 安全体系 S2 · L7 低余额并行闸门（SEC-FR-126）：提交时 acquire，worker 终态 release。 */
    private final InflightGateService inflightGate;
    /** 2x 第三轮 C3：每用户媒体并发闸门（video/image 独立计数），提交 acquire / worker 终态 release。 */
    private final MediaInflightGateService mediaInflightGate;
    /** 媒体提交指标（media.task.submitted）。 */
    private final BizMetrics bizMetrics;
    /** 全局默认视频模型（media.default.video-model）：未指定 model 提交时的优先回落。 */
    private final com.superprogrammer.system.service.SystemSettingService systemSettingService;
    /** 审计：submit 编程式落库（关联键 targetId=taskId，问题修复 #8）。 */
    private final AuditLogService auditLogService;
    /** 计划5 Step5：组池预检（成员身份+组池余额，估价值）。 */
    private final com.superprogrammer.projectgroup.service.ProjectGroupWalletService groupWalletService;
    /** 计划5 Step5：成员限额余量预检（findMember 探针）。 */
    private final com.superprogrammer.projectgroup.service.ProjectGroupService projectGroupService;
    /** 计划5 Step5：提交时估价快照（estimated_cost，回收在途上限用）。 */
    private final com.superprogrammer.billing.service.PricingService pricingService;
    private final com.superprogrammer.billing.service.PointsRatioService pointsRatioService;
    /** 7x（V155）：提交即预扣预估积分，完工按实多退少补。 */
    private final com.superprogrammer.billing.service.MediaBillingService mediaBillingService;
    /** C1（17x-2）：预估个人口径——成员/管理双卡可用读数。 */
    private final com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper memberMapper;
    private final com.superprogrammer.projectgroup.service.MemberBudgetService memberBudgetService;

    /**
     * 提交生成任务。
     *
     * @param prompt        提示词（必填）
     * @param ratio         画面比例（官方 ratio，null 默认 16:9）
     * @param duration      时长秒（4–15）
     * @param resolution    分辨率（null 默认 720p）
     * @param watermark     水印开关（null 默认 false）
     * @param generateAudio 生成原生音频开关（null 默认 false）
     * @param taskType      TEXT2VIDEO / IMAGE2VIDEO（attachments 非空时服务端强制 IMAGE2VIDEO）
     * @param refFileId     旧版单首帧参考图 file_id（与 attachments 互斥，保留兼容）
     * @param attachments   多模态参考附件（图/视频/音频，上限按模型能力）
     * @param model         视频模型 id（null 则取默认 provider 首个模型）
     * @param userId        提交用户（nullable：系统调用）
     * @param admin         是否 admin（附件归属校验旁路）
     * @param frameRole     参考帧位置 "first"/"last"（仅 IMAGE2VIDEO + refFileId；null/first=首帧，last=尾帧）
     * @return 任务 id
     */

    /**
     * 12 参重载（向后兼容，frameRole=null = 首帧默认）—— 旧调用方/测试无需改签名。
     */
    public Long submit(String prompt, String ratio, Integer duration, String resolution,
                       Boolean watermark, Boolean generateAudio, String taskType,
                       String refFileId, List<AttachmentRef> attachments,
                       String model, Long userId, boolean admin) {
        return submit(prompt, ratio, duration, resolution, watermark, generateAudio, taskType,
                refFileId, attachments, model, userId, admin, null);
    }

    public Long submit(String prompt, String ratio, Integer duration, String resolution,
                       Boolean watermark, Boolean generateAudio, String taskType,
                       String refFileId, List<AttachmentRef> attachments,
                       String model, Long userId, boolean admin, String frameRole) {
        return submit(prompt, ratio, duration, resolution, watermark, generateAudio, taskType,
                refFileId, attachments, model, userId, admin, frameRole, null);
    }

    /** 计划5 Step5：+projectGroupId 组池计费版本（null=个人钱包，现状）。 */
    public Long submit(String prompt, String ratio, Integer duration, String resolution,
                       Boolean watermark, Boolean generateAudio, String taskType,
                       String refFileId, List<AttachmentRef> attachments,
                       String model, Long userId, boolean admin, String frameRole,
                       Long projectGroupId) {
        return submit(prompt, ratio, duration, resolution, watermark, generateAudio, taskType,
                refFileId, attachments, model, userId, admin, frameRole, projectGroupId, null);
    }

    /** HHX-10：+sourceTaskId 再生成源任务版本（仅 model 后缀 -regeneration 生效，其余忽略）。 */
    public Long submit(String prompt, String ratio, Integer duration, String resolution,
                       Boolean watermark, Boolean generateAudio, String taskType,
                       String refFileId, List<AttachmentRef> attachments,
                       String model, Long userId, boolean admin, String frameRole,
                       Long projectGroupId, Long sourceTaskId) {
        if (!properties.isGenEnabled()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频生成功能未开启");
        }

        // 0) 余额预检（Chunk F 联动）：余额>0 才允许提交生成任务，≤0 拒（task 不建）。
        // userId=null（系统调用）/billing.enabled=false → requireAffordable 内部跳过（放行）。
        // 余额复用：返回值直接喂给闸门，省一次重复查库
        // 计划5 Step5：带 gid → 组池预检（非成员 403/组池尽 40201），组池余额喂闸门（语义同个人）
        java.math.BigDecimal balance = projectGroupId == null
                ? walletService.requireAffordable(userId)
                : groupWalletService.requireAffordableGroup(projectGroupId, userId, "VIDEO");
        // L7：低余额用户超在途上限 → 42902（计数由 worker 终态 release 配对释放）；
        // 但 acquire 之后、task 落库之前的任何异常（provider 缺失/参数校验/DB 异常）都不会有 worker
        // 接手 → 此处配对释放，否则低余额用户一次失败提交即自我锁死至 TTL（30min）
        boolean held = inflightGate.acquire(userId, balance);
        // C3：每用户视频并发上限（15x 落地，D5 默认 2 可调）→ 42904；同样落库前异常须配对释放
        boolean mediaHeld = false;
        try {
            mediaHeld = mediaInflightGate.acquire(userId, MediaInflightGateService.KIND_VIDEO);
            Long taskId = doSubmit(prompt, ratio, duration, resolution, watermark, generateAudio, taskType,
                    refFileId, attachments, model, userId, admin, frameRole, projectGroupId, balance,
                    sourceTaskId);
            // 指标：落库成功才计提交（acquire 失败/参数校验失败不计）
            bizMetrics.mediaSubmit(MediaGenTask.TYPE_TEXT2IMAGE.equals(taskType)
                    || MediaGenTask.TYPE_IMAGE2IMAGE.equals(taskType)
                    ? BizMetrics.MEDIA_IMAGE : BizMetrics.MEDIA_VIDEO);
            return taskId;
        } catch (RuntimeException e) {
            if (held) {
                inflightGate.release(userId);
            }
            if (mediaHeld) {
                mediaInflightGate.release(userId, MediaInflightGateService.KIND_VIDEO);
            }
            throw e;
        }
    }

    private Long doSubmit(String prompt, String ratio, Integer duration, String resolution,
                          Boolean watermark, Boolean generateAudio, String taskType,
                          String refFileId, List<AttachmentRef> attachments,
                          String model, Long userId, boolean admin, String frameRole,
                          Long projectGroupId, java.math.BigDecimal poolBalance, Long sourceTaskId) {

        // 1) 解析 provider + model（指定 model 时跨 VIDEO provider 反查；未指定优先管理员配置的
        //    全局默认视频模型（media.default.video-model），未配置/已失效回落旧默认 provider 路径）
        LlmProviderEntity provider;
        String resolvedModel;
        if (model == null || model.isBlank()) {
            String defaultVideoModel = systemSettingService.getDefaultVideoModel();
            LlmProviderEntity defaultHit = defaultVideoModel == null ? null
                    : mediaModelService.resolveProviderByModel(defaultVideoModel);
            if (defaultHit != null) {
                provider = defaultHit;
                resolvedModel = defaultVideoModel;
            } else {
                if (defaultVideoModel != null) {
                    log.warn("全局默认视频模型 {} 已失效（不在任何 ACTIVE VIDEO provider），回落默认 provider 首模型", defaultVideoModel);
                }
                provider = mediaModelService.defaultProvider();
                if (provider == null) {
                    throw new BusinessException(ErrorCode.NOT_FOUND,
                            "未找到默认视频 provider(name=" + properties.getProviderName()
                                    + ")，请先在「全局模型供应商」建一条 VIDEO 类 provider");
                }
                resolvedModel = mediaModelService.firstModelOf(provider);
                if (resolvedModel == null) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "视频 provider 未配置模型列表（models）");
                }
            }
        } else {
            provider = mediaModelService.resolveProviderByModel(model);
            if (provider == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "模型不可用: " + model + "（不在任何 ACTIVE 媒体 provider 的 models 列表中）");
            }
            resolvedModel = model;
        }
        MediaModelCapability cap = capabilityService.resolve(resolvedModel, provider.getConfig());

        // HHX-9/10：附属任务分流——context-ir（提示词增强，输入同生成）/ regeneration（2K 再生成，输入仅源任务）
        boolean auxContextIr = isContextIrModel(resolvedModel);
        boolean auxRegeneration = isRegenerationModel(resolvedModel);
        MediaGenTask sourceTask = null;
        if (auxRegeneration) {
            sourceTask = requireRegenerationSource(sourceTaskId, provider, userId, admin);
        } else {
            // 2) 参数校验（基础白名单 + 模型能力上限）
            validate(prompt, ratio, duration, resolution, generateAudio, taskType, refFileId, attachments,
                    cap, userId, admin);
        }

        // 3) attachments 非空 → 服务端派生 IMAGE2VIDEO（多模态参考生视频）；附属任务固定专属类型
        String resolvedType = auxRegeneration ? MediaGenTask.TYPE_REGENERATION
                : auxContextIr ? MediaGenTask.TYPE_CONTEXT_IR
                : (attachments != null && !attachments.isEmpty())
                ? MediaGenTask.TYPE_IMAGE2VIDEO
                : (taskType == null || taskType.isBlank() ? MediaGenTask.TYPE_TEXT2VIDEO : taskType);

        Map<String, Object> config = new HashMap<>();
        if (auxRegeneration) {
            // 再生成入参极简：源任务（平台 id 留痕 + 上游 ark id 出站用）+ 继承时长 + 锁 2k
            config.put("sourceTaskId", sourceTask.getId());
            config.put("sourceArkTaskId", sourceTask.getArkTaskId());
            config.put("duration", regenerationDurationOf(sourceTask));
            config.put("resolution", "2k");
        } else {
            config.put("prompt", prompt);
            config.put("ratio", ratio);
            config.put("duration", duration);
            config.put("resolution", resolution);
            config.put("watermark", Boolean.TRUE.equals(watermark));
            config.put("generateAudio", Boolean.TRUE.equals(generateAudio));
            if (refFileId != null) config.put("refFileId", refFileId);
            // C2 参考帧位置：归一化（只认 last=尾帧，其余 first=首帧默认），仅 refFileId 通道有意义
            if (refFileId != null) {
                config.put("frameRole", "last".equalsIgnoreCase(frameRole) ? "last" : "first");
            }
            if (attachments != null && !attachments.isEmpty()) {
                List<Map<String, String>> list = new ArrayList<>(attachments.size());
                for (AttachmentRef a : attachments) {
                    // F3：kind 归一化后落库（校验用的就是归一化值；worker/Ark 映射直接查表不再二次处理）
                    // frameRole 归一化：仅 first_frame/last_frame 落库（null 省略，=普通参考图）
                    String kind = a.getKind().trim().toLowerCase();
                    String role = normalizeFrameRole(a.getFrameRole(), kind);
                    Map<String, String> item = new java.util.LinkedHashMap<>();
                    item.put("fileId", a.getFileId());
                    item.put("kind", kind);
                    if (role != null) item.put("frameRole", role);
                    if (a.getName() != null && !a.getName().isBlank()) item.put("name", a.getName().strip());
                    list.add(item);
                }
                config.put("attachments", list);
            }
        }

        MediaGenTask task = new MediaGenTask();
        task.setUserId(userId);
        task.setProviderId(provider.getId());
        task.setModel(resolvedModel);
        task.setTaskType(resolvedType);
        task.setStatus(MediaGenTask.STATUS_PENDING);
        task.setRequestConfig(toJson(config));
        task.setStatusFlag(MediaGenTask.FLAG_SUCCESS);
        task.setAttempt(0);
        // 问题修复 #6：盖戳提交者 IP（worker 终态审计取用，worker 无 MDC）
        task.setClientIp(MDC.get("clientIp"));
        // 计划5 Step5：估价快照（V133 estimated_cost，积分口径；TOKEN 模式提交期无 token 维度/价表缺价记 0+WARN）
        boolean hasRefVideo = attachments != null && attachments.stream()
                .anyMatch(a -> a.getKind() != null && "video".equalsIgnoreCase(a.getKind().trim()));
        // HHX-9/10：附属任务估价——context-ir 走 CHAT 估算公式（结算按实 token 多退少补）；
        // regeneration 按源任务继承时长 × 锁 2k 秒价（价表 regeneration 行）。
        java.math.BigDecimal estimatedPoints;
        if (auxContextIr) {
            estimatedPoints = estimateContextIrPoints(provider.getId(), resolvedModel, prompt, attachments);
        } else if (auxRegeneration) {
            estimatedPoints = estimateVideoPoints(provider.getId(), resolvedModel,
                    regenerationDurationOf(sourceTask), false, "2k");
        } else {
            estimatedPoints = estimateVideoPoints(provider.getId(), resolvedModel,
                    duration, hasRefVideo, resolution);
        }
        // 2026-08-25 fail-closed 硬闸：估不出价/估价为 0 → 一律拒提交（个人/组池同闸）。
        // 估价 0 会跳过下方三重预检与预扣——余额不足用户可无限白嫖真实生成的视频（17x 安全审计后续发现）。
        // 系统调用（userId=null）/计费开关关 跳过；免费模型请配极小正值价。
        if (userId != null && walletService.isEnabled() && estimatedPoints.signum() <= 0) {
            throw new BusinessException(ErrorCode.PRICING_NOT_FOUND,
                    "该模型未配置有效预估价（est_per_resolution/秒价），任务未提交——请联系管理员在价表配置中补齐");
        }
        if (projectGroupId == null) {
            // 7x-2（V152）：个人钱包同组池口径——余额 < 预估消耗即拒（task 不建），
            // 防「100 积分提交 2000 积分任务」。估价值 0（无价表/TOKEN 未配预估秒价）不拦，与组池同。
            if (poolBalance != null && estimatedPoints.signum() > 0
                    && poolBalance.compareTo(estimatedPoints) < 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS,
                        "积分不足（预估消耗 " + estimatedPoints.stripTrailingZeros().toPlainString()
                                + "），任务未提交");
            }
        } else {
            // 组任务三重预检（估价值）：成员身份已在 submit 入口验；此处=组池余量 + 成员限额余量，不足即拒 task 不建
            if (poolBalance != null && estimatedPoints.signum() > 0
                    && poolBalance.compareTo(estimatedPoints) < 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS,
                        "项目组积分不足（预估消耗 " + estimatedPoints + "），任务未提交");
            }
            var member = projectGroupService.findMember(projectGroupId, userId);
            if (member != null && member.getQuotaLimitPoints() != null
                    && member.getUsedPoints().add(estimatedPoints).compareTo(member.getQuotaLimitPoints()) > 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "超出组长配置的成员积分限额（预估消耗 " + estimatedPoints + "），任务未提交");
            }
            task.setProjectGroupId(projectGroupId);
        }
        task.setEstimatedCost(estimatedPoints);
        taskMapper.insert(task);

        // 7x（V155）：提交即按预估积分预扣（完工按实多退少补）——防多任务并行「预检都过、结算不够扣」。
        // 预扣失败（预检后被并发耗尽余额/组池）→ 删任务行并拒绝（语义同预检不足）；est=0（无价表）不扣不拦。
        if (estimatedPoints != null && estimatedPoints.signum() > 0) {
            try {
                boolean held = mediaBillingService.holdMediaEstimated(userId, estimatedPoints,
                        com.superprogrammer.billing.entity.LlmUsageLogEntity.KIND_VIDEO,
                        task.getId(), projectGroupId);
                task.setHoldApplied(held);
                if (held) {
                    taskMapper.updateById(task);
                }
            } catch (BusinessException be) {
                taskMapper.deleteById(task.getId());
                throw be;
            }
        }

        // 问题修复 #8：submit 编程式落审计，targetId=taskId（与 worker 终态行关联）
        Map<String, Object> submitDetail = new LinkedHashMap<>();
        submitDetail.put("model", resolvedModel);
        submitDetail.put("taskType", resolvedType);
        if (auxRegeneration) {
            submitDetail.put("sourceTaskId", sourceTask.getId());
            submitDetail.put("duration", regenerationDurationOf(sourceTask));
        } else {
            submitDetail.put("ratio", ratio);
            submitDetail.put("duration", duration);
            submitDetail.put("resolution", resolution);
        }
        auditLogService.recordTask("media", "video_submit", "media_gen_task", String.valueOf(task.getId()),
                userId, MDC.get("username"), task.getClientIp(), toJson(submitDetail),
                com.superprogrammer.common.audit.AuditLogEntity.RESULT_SUCCESS);

        log.info("提交视频生成任务 taskId={} userId={} type={} model={} ratio={} res={} audio={} 附件={}{}",
                task.getId(), userId, resolvedType, resolvedModel, ratio, resolution, generateAudio,
                attachments == null ? 0 : attachments.size(),
                auxRegeneration ? " 源任务=" + sourceTask.getId() : "");
        return task.getId();
    }

    // ---------- 图片任务提交（Seedream 同步生图，与视频提交并列） ----------

    /**
     * 提交生图任务。参数按模型实际能力校验（{@link ImageModelCapability}），不支持的字段直接拒。
     * 参考图按张归属+大小+格式校验（复用 {@link #checkAttachmentOwnership}，kind=image）。
     * 提示词与参考图二选一驱动任务类型：有参考图→IMAGE2IMAGE，无→TEXT2IMAGE。
     *
     * @param refFileIds 参考图 file_id 列表（资产库选取；纯文生图传 null/空）
     * @param size       "2K"/"3K"/"4K" 预设 或 自定义"宽x高"（须 supportsWhSize）
     * @param outputFormat jpeg/png（须在 outputFormats）
     * @param optimizeMode standard/fast（须在 optimizeModes）
     * @param sequential  组图 auto/disabled（须 supportsSequential）
     * @param maxImages   组图最大数（≤ maxSequentialImages）
     * @param guidanceScale 引导尺度（须 supportsGuidanceScale，∈[min,max]）
     * @param webSearch   联网搜索（须 supportsWebSearch）
     * @return 任务 id
     */
    public Long submitImage(String prompt, List<String> refFileIds, String size, String outputFormat,
                            Boolean watermark, Double guidanceScale, String optimizeMode,
                            String sequential, Integer maxImages, Boolean webSearch,
                            String model, Long userId, boolean admin) {
        return submitImage(prompt, refFileIds, size, outputFormat, watermark, guidanceScale, optimizeMode,
                sequential, maxImages, webSearch, model, userId, admin, null, null);
    }

    /** 计划5 Step5：+projectGroupId 组池计费版本（null=个人钱包，现状）。C3（6x/Q5）：+ratio 比例模式。 */
    public Long submitImage(String prompt, List<String> refFileIds, String size, String outputFormat,
                            Boolean watermark, Double guidanceScale, String optimizeMode,
                            String sequential, Integer maxImages, Boolean webSearch,
                            String model, Long userId, boolean admin, Long projectGroupId, String ratio) {
        if (!properties.isGenEnabled()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "图片生成功能未开启");
        }
        // 余额预检（与视频同一咽喉）：≤0 拒；系统调用/billing 关则放行
        // 计划5 Step5：带 gid → 组池预检（非成员 403/组池尽 40201）
        java.math.BigDecimal poolBalance = projectGroupId == null
                ? walletService.requireAffordable(userId)
                : groupWalletService.requireAffordableGroup(projectGroupId, userId, "IMAGE");
        // C3：每用户生图并发上限（15x 落地，D5 默认 3 可调）→ 42904；落库前异常配对释放（同视频）
        boolean mediaHeld = false;
        try {
            mediaHeld = mediaInflightGate.acquire(userId, MediaInflightGateService.KIND_IMAGE);
            return doSubmitImage(prompt, refFileIds, size, outputFormat, watermark, guidanceScale,
                    optimizeMode, sequential, maxImages, webSearch, model, userId, admin,
                    projectGroupId, poolBalance, ratio);
        } catch (RuntimeException e) {
            if (mediaHeld) {
                mediaInflightGate.release(userId, MediaInflightGateService.KIND_IMAGE);
            }
            throw e;
        }
    }

    private Long doSubmitImage(String prompt, List<String> refFileIds, String size, String outputFormat,
                               Boolean watermark, Double guidanceScale, String optimizeMode,
                               String sequential, Integer maxImages, Boolean webSearch,
                               String model, Long userId, boolean admin,
                               Long projectGroupId, java.math.BigDecimal poolBalance, String ratio) {

        // 解析 IMAGE provider（指定 model 跨 IMAGE provider 反查；图片任务无默认 provider 回退）
        LlmProviderEntity provider = mediaModelService.resolveImageProviderByModel(model);
        if (provider == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "生图模型不可用: " + model + "（不在任何 ACTIVE IMAGE provider 的 models 列表中，"
                            + "请先在「全局模型供应商」建一条 IMAGE 类 provider 并配置该模型）");
        }
        ImageModelCapability cap = capabilityService.resolveImage(model, provider.getConfig());

        // C3（6x/Q5）：比例模式——与显式宽x高互斥；比例+档位 → 推导 WxH 覆盖 size 后走既有链路
        String requestedRatio = null;
        if (ratio != null && !ratio.isBlank()) {
            if (size != null && size.matches("\\d+\\s*[xX×]\\s*\\d+")) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "ratio 与自定义宽x高 size 互斥（二选一：选比例则 size 留空或传档位 2K/3K/…）");
            }
            size = ImageSizeDeriver.derive(ratio, size, cap);
            requestedRatio = ratio.trim();
        }

        // 参数校验（提示词 + 参考图 + 各模型特性字段）
        validateImage(prompt, refFileIds, size, outputFormat, watermark, guidanceScale, optimizeMode,
                sequential, maxImages, webSearch, cap, userId, admin);

        String resolvedType = (refFileIds != null && !refFileIds.isEmpty())
                ? MediaGenTask.TYPE_IMAGE2IMAGE
                : MediaGenTask.TYPE_TEXT2IMAGE;

        Map<String, Object> config = new HashMap<>();
        config.put("prompt", prompt == null ? "" : prompt);
        if (size != null && !size.isBlank()) config.put("size", size);
        // C3：原始比例留痕（size 已是推导后宽x高，审计/参数回显用）
        if (requestedRatio != null) config.put("ratio", requestedRatio);
        if (outputFormat != null && !outputFormat.isBlank()) config.put("outputFormat", outputFormat);
        config.put("watermark", watermark == null ? cap.isWatermarkDefault() : watermark);
        if (guidanceScale != null) config.put("guidanceScale", guidanceScale);
        if (optimizeMode != null && !optimizeMode.isBlank()) config.put("optimizeMode", optimizeMode);
        if (sequential != null && !sequential.isBlank()) config.put("sequential", sequential);
        if (maxImages != null) config.put("maxImages", maxImages);
        if (Boolean.TRUE.equals(webSearch)) config.put("webSearch", true);
        if (refFileIds != null && !refFileIds.isEmpty()) config.put("refFileIds", refFileIds);

        MediaGenTask task = new MediaGenTask();
        task.setUserId(userId);
        task.setProviderId(provider.getId());
        task.setModel(model);
        task.setTaskType(resolvedType);
        task.setStatus(MediaGenTask.STATUS_PENDING);
        task.setRequestConfig(toJson(config));
        task.setStatusFlag(MediaGenTask.FLAG_SUCCESS);
        task.setAttempt(0);
        // 问题修复 #6：盖戳提交者 IP
        task.setClientIp(MDC.get("clientIp"));
        // 计划5 Step5：估价快照（组图按 maxImages 估；价表缺价记 0+WARN）
        java.math.BigDecimal estimatedPoints = estimateImagePoints(provider.getId(), model,
                maxImages == null || maxImages <= 0 ? 1 : maxImages);
        // 2026-08-25 fail-closed 硬闸（同视频）：估价为 0 → 拒提交，防估 0 跳过预检/预扣白嫖。
        if (userId != null && walletService.isEnabled() && estimatedPoints.signum() <= 0) {
            throw new BusinessException(ErrorCode.PRICING_NOT_FOUND,
                    "该模型未配置有效单价（price_per_image），任务未提交——请联系管理员在价表配置中补齐");
        }
        if (projectGroupId == null) {
            // 7x-2（V152）：个人钱包余额 < 预估消耗即拒（与视频/组池同口径）
            if (poolBalance != null && estimatedPoints.signum() > 0
                    && poolBalance.compareTo(estimatedPoints) < 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS,
                        "积分不足（预估消耗 " + estimatedPoints.stripTrailingZeros().toPlainString()
                                + "），任务未提交");
            }
        } else {
            if (poolBalance != null && estimatedPoints.signum() > 0
                    && poolBalance.compareTo(estimatedPoints) < 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS,
                        "项目组积分不足（预估消耗 " + estimatedPoints + "），任务未提交");
            }
            var member = projectGroupService.findMember(projectGroupId, userId);
            if (member != null && member.getQuotaLimitPoints() != null
                    && member.getUsedPoints().add(estimatedPoints).compareTo(member.getQuotaLimitPoints()) > 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "超出组长配置的成员积分限额（预估消耗 " + estimatedPoints + "），任务未提交");
            }
            task.setProjectGroupId(projectGroupId);
        }
        task.setEstimatedCost(estimatedPoints);
        taskMapper.insert(task);

        // 7x（V155）：提交即按预估积分预扣（与视频同口径，完工按实多退少补）。
        if (estimatedPoints != null && estimatedPoints.signum() > 0) {
            try {
                boolean held = mediaBillingService.holdMediaEstimated(userId, estimatedPoints,
                        com.superprogrammer.billing.entity.LlmUsageLogEntity.KIND_IMAGE,
                        task.getId(), projectGroupId);
                task.setHoldApplied(held);
                if (held) {
                    taskMapper.updateById(task);
                }
            } catch (BusinessException be) {
                taskMapper.deleteById(task.getId());
                throw be;
            }
        }

        // 问题修复 #8：submit 编程式落审计，targetId=taskId
        Map<String, Object> submitDetail = new LinkedHashMap<>();
        submitDetail.put("model", model);
        submitDetail.put("taskType", resolvedType);
        submitDetail.put("size", size);
        submitDetail.put("refImageCount", refFileIds == null ? 0 : refFileIds.size());
        auditLogService.recordTask("media", "image_submit", "media_gen_task", String.valueOf(task.getId()),
                userId, MDC.get("username"), task.getClientIp(), toJson(submitDetail),
                com.superprogrammer.common.audit.AuditLogEntity.RESULT_SUCCESS);

        log.info("提交图片生成任务 taskId={} userId={} type={} model={} size={} 参考图={}",
                task.getId(), userId, resolvedType, model, size, refFileIds == null ? 0 : refFileIds.size());
        return task.getId();
    }

    /**
     * 图片参数校验：提示词 + 参考图（数量/格式/归属/大小）+ size + 输出格式 + 优化模式 +
     * 组图 + 引导尺度 + 联网搜索，逐项对照 {@link ImageModelCapability} 拒非法。
     * 「不支持的参数传了值即拒」——提交侧挡死，worker/provider 不再二次处理。
     */
    private void validateImage(String prompt, List<String> refFileIds, String size, String outputFormat,
                               Boolean watermark, Double guidanceScale, String optimizeMode,
                               String sequential, Integer maxImages, Boolean webSearch,
                               ImageModelCapability cap, Long userId, boolean admin) {
        // Seedream 允许纯图（无 prompt）？官方 prompt 必填，但空 prompt 也兜底放行（provider 传空串）。
        if (prompt != null && prompt.length() > PROMPT_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词长度超限（≤" + PROMPT_MAX_LEN + "）");
        }
        // 参考图：纯文生图允许空；非空则按张校验（数量/格式/归属/大小）。
        int refCount = refFileIds == null ? 0 : refFileIds.size();
        if (refCount > 0) {
            if (cap.getRefImageMax() <= 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该模型不支持参考图");
            }
            if (refCount > cap.getRefImageMax()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "参考图超限（该模型 ≤" + cap.getRefImageMax() + " 张，当前 " + refCount + "）");
            }
            for (String fid : refFileIds) {
                checkImageRef(fid, cap, userId, admin);
            }
        }
        // size：预设枚举 或 自定义宽x高
        if (size != null && !size.isBlank() && !isValidSize(size, cap)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "尺寸非法: " + size + "（可选预设 " + cap.getSizePresets()
                            + (cap.isSupportsWhSize() ? " 或自定义「宽x高」" : "") + "）");
        }
        // 输出格式枚举
        if (outputFormat != null && !outputFormat.isBlank()
                && !cap.getOutputFormats().contains(outputFormat)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "输出格式非法: " + outputFormat + "（可选 " + cap.getOutputFormats() + "）");
        }
        // 提示词优化模式枚举
        if (optimizeMode != null && !optimizeMode.isBlank()
                && !cap.getOptimizeModes().contains(optimizeMode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "优化模式非法: " + optimizeMode + "（该模型可选 " + cap.getOptimizeModes() + "）");
        }
        // 引导尺度（pro 独有）
        if (guidanceScale != null) {
            if (!cap.isSupportsGuidanceScale()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该模型不支持引导尺度（guidance_scale）");
            }
            if (guidanceScale < cap.getGuidanceMin() || guidanceScale > cap.getGuidanceMax()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "引导尺度须 ∈ [" + cap.getGuidanceMin() + ", " + cap.getGuidanceMax() + "]");
            }
        }
        // 组图（lite 独有）
        if (sequential != null && !sequential.isBlank()) {
            if (!cap.isSupportsSequential()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该模型不支持组图（sequential_image_generation）");
            }
            if (!"auto".equalsIgnoreCase(sequential) && !"disabled".equalsIgnoreCase(sequential)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "组图模式非法: " + sequential + "（auto/disabled）");
            }
        }
        if (maxImages != null && (maxImages < 1 || maxImages > cap.getMaxSequentialImages())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "组图张数须 ∈ [1, " + cap.getMaxSequentialImages() + "]");
        }
        // 联网搜索（lite 独有）
        if (Boolean.TRUE.equals(webSearch) && !cap.isSupportsWebSearch()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该模型不支持联网搜索");
        }
    }

    /** size 合法性：命预设枚举，或（支持自定义宽x高时）匹配「宽x高」数字模式。 */
    private boolean isValidSize(String size, ImageModelCapability cap) {
        if (cap.getSizePresets().contains(size)) {
            return true;
        }
        return cap.isSupportsWhSize() && size.matches("\\d+\\s*[xX×]\\s*\\d+");
    }

    /** 参考图校验：归属 + 大小 + MIME 粗匹配 + 扩展名格式白名单（lite 含 webp 等多格式，pro 仅 jpeg/png）。 */
    private void checkImageRef(String fileId, ImageModelCapability cap, Long userId, boolean admin) {
        StoredFileEntity meta = fileStorageService.findMeta(fileId);
        if (meta == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "参考图不存在: " + fileId);
        }
        if (!admin && userId != null && !userId.equals(meta.getOwnerUserId())) {
            if (!assetService.isAttachmentFileAccessible(fileId, userId, admin)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权使用该参考图: " + fileId);
            }
        }
        long maxBytes = MediaStorageService.KIND_MAX_BYTES.getOrDefault("image", 8L * 1024 * 1024);
        if (meta.getSize() != null && meta.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "参考图过大: " + meta.getOriginalName() + "（≤" + (maxBytes / 1024 / 1024) + "MB）");
        }
        // MIME 粗匹配：mime 非 image/* 拒（无 mime 放行，落 provider 报错兜底）
        String mime = meta.getMime();
        if (mime != null && !mime.isBlank() && !mime.startsWith("image/")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "参考图类型不符: " + meta.getOriginalName() + "（" + mime + "）");
        }
        // 扩展名格式白名单（模型差异：lite 多格式，pro 仅 jpeg/png；jpg/jpeg 互通）
        String name = meta.getOriginalName() == null ? "" : meta.getOriginalName().toLowerCase();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (!ext.isBlank() && !cap.getRefImageFormats().isEmpty()) {
            List<String> allowed = cap.getRefImageFormats();
            boolean ok = allowed.contains(ext)
                    || ("jpg".equals(ext) && allowed.contains("jpeg"))
                    || ("jpeg".equals(ext) && allowed.contains("jpg"));
            if (!ok) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "参考图格式不支持: " + meta.getOriginalName() + "（该模型允许 " + allowed + "）");
            }
        }
    }

    private void validate(String prompt, String ratio, Integer duration, String resolution,
                          Boolean generateAudio, String taskType, String refFileId,
                          List<AttachmentRef> attachments, MediaModelCapability cap,
                          Long userId, boolean admin) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        if (prompt.length() > PROMPT_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词长度超限（≤" + PROMPT_MAX_LEN + "）");
        }
        int maxDuration = Math.min(cap.getMaxDuration(), properties.getMaxDuration());
        if (duration == null || duration < cap.getMinDuration() || duration > maxDuration) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "时长须 ∈ [" + cap.getMinDuration() + ", " + maxDuration + "]");
        }
        if (ratio != null && !ratio.isBlank() && !cap.getSupportedRatios().contains(ratio)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "画面比例非法: " + ratio);
        }
        if (resolution != null && !resolution.isBlank() && !cap.getSupportedResolutions().contains(resolution)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该模型不支持分辨率: " + resolution);
        }
        Integer maxRank = RES_RANK.get(properties.getMaxRes());
        if (maxRank == null) maxRank = RES_RANK.get("720p");
        if (resolution != null && RES_RANK.getOrDefault(resolution, 0) > maxRank) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "分辨率超上限（≤" + properties.getMaxRes() + "）");
        }
        if (Boolean.TRUE.equals(generateAudio) && !cap.isSupportsGenerateAudio()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该模型不支持生成音频");
        }
        if (attachments != null && !attachments.isEmpty()) {
            if (refFileId != null && !refFileId.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "attachments 与 refFileId 互斥，请只用一种参考方式");
            }
            validateAttachments(attachments, cap, userId, admin);
            return;
        }
        // 旧路径：无 attachments 时沿用 taskType + refFileId 规则（null/blank 视为 TEXT2VIDEO）
        String tt = (taskType == null || taskType.isBlank()) ? MediaGenTask.TYPE_TEXT2VIDEO : taskType;
        if (!MediaGenTask.TYPE_TEXT2VIDEO.equals(tt) && !MediaGenTask.TYPE_IMAGE2VIDEO.equals(tt)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务类型非法: " + taskType);
        }
        if (MediaGenTask.TYPE_IMAGE2VIDEO.equals(tt)) {
            if (refFileId == null || refFileId.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "图生视频须提供参考图");
            }
            if (cap.getMaxImages() < 1) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该模型不支持参考图");
            }
        }
    }

    /**
     * 附件校验：类型白名单 + 分类上限 + 总数上限 + 归属校验（防 IDOR）+ MIME 粗匹配。
     */
    private void validateAttachments(List<AttachmentRef> attachments, MediaModelCapability cap,
                                     Long userId, boolean admin) {
        if (attachments.size() > cap.getMaxAttachments()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "附件总数超限（≤" + cap.getMaxAttachments() + "，当前 " + attachments.size() + "）");
        }
        int images = 0, videos = 0, audios = 0;
        int firstFrame = 0, lastFrame = 0;
        for (AttachmentRef a : attachments) {
            if (a.getName() != null && a.getName().length() > 255) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "附件名称不能超过 255 字符");
            }
            String kind = a.getKind() == null ? "" : a.getKind().trim().toLowerCase();
            if (!ATTACHMENT_KINDS.contains(kind)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "附件类型非法: " + a.getKind() + "（image/video/audio）");
            }
            switch (kind) {
                case "image" -> images++;
                case "video" -> videos++;
                case "audio" -> audios++;
                default -> { /* 白名单已挡 */ }
            }
            // frameRole 仅 image 合法；首/尾帧全局各 ≤1（SeedDance content[] 契约）
            String role = normalizeFrameRole(a.getFrameRole(), kind);
            if (role != null) {
                if ("first_frame".equals(role)) {
                    if (firstFrame++ > 0) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "首帧最多 1 张");
                    }
                } else if (lastFrame++ > 0) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "尾帧最多 1 张");
                }
            }
            checkAttachmentOwnership(a.getFileId(), kind, userId, admin);
        }
        // Ark 契约：首/尾帧模式不能与任何 reference_image/video/audio 混用。
        int referenceImages = images - firstFrame - lastFrame;
        int referenceMedia = referenceImages + videos + audios;
        if ((firstFrame > 0 || lastFrame > 0) && referenceMedia > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "首帧/尾帧不能与参考媒体同时使用（参考图、参考视频、参考音频），请选择一种生成模式");
        }
        if (images > cap.getMaxImages()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "参考图超限（该模型 ≤" + cap.getMaxImages() + " 张，当前 " + images + "）");
        }
        if (videos > cap.getMaxVideos()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    cap.getMaxVideos() == 0 ? "该模型不支持参考视频"
                            : "参考视频超限（该模型 ≤" + cap.getMaxVideos() + " 个，当前 " + videos + "）");
        }
        if (audios > cap.getMaxAudios()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    cap.getMaxAudios() == 0 ? "该模型不支持参考音频"
                            : "参考音频超限（该模型 ≤" + cap.getMaxAudios() + " 个，当前 " + audios + "）");
        }
        if (videos > 0 && !properties.isReferenceVideoConfigured()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "参考视频功能未配置：需要 Ark 可访问的 HTTPS 公网地址和签名密钥");
        }
    }

    /**
     * 归一化附件 frameRole：仅 kind=image 接受 first_frame/last_frame（大小写不敏感）；
     * 非 image 或非法值 → null（=普通参考图）。供 config 落库与校验共用同一判定，避免两处漂移。
     */
    private String normalizeFrameRole(String raw, String kind) {
        if (raw == null || raw.isBlank() || !"image".equals(kind)) return null;
        String r = raw.trim().toLowerCase();
        return "first_frame".equals(r) || "last_frame".equals(r) ? r : null;
    }

    /**
     * 附件归属 + 大小 + MIME 校验。提交即拒（400/403），不拖到 worker 异步失败。
     * admin 与系统调用（userId=null）旁路归属校验。
     * F2：大小按落库 meta.size 预检（与 MediaStorageService 同一上限表），
     * 超限在提交时 400，worker 不再为超限文件全量读流入堆。
     */
    private void checkAttachmentOwnership(String fileId, String kind, Long userId, boolean admin) {
        StoredFileEntity meta = fileStorageService.findMeta(fileId);
        if (meta == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "附件不存在: " + fileId);
        }
        if (!admin && userId != null && !userId.equals(meta.getOwnerUserId())) {
            // 资产库文件：按项目成员身份放行（与 canvas bridge resolve 一致），不要求文件归属相等
            if (!assetService.isAttachmentFileAccessible(fileId, userId, admin)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权使用该附件: " + fileId);
            }
        }
        long maxBytes = MediaStorageService.KIND_MAX_BYTES.getOrDefault(kind, Long.MAX_VALUE);
        if (meta.getSize() != null && meta.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "附件过大: " + meta.getOriginalName() + "（" + kind + " ≤" + (maxBytes / 1024 / 1024) + "MB）");
        }
        String mime = meta.getMime();
        if (mime != null && !mime.isBlank() && !mime.startsWith(kind + "/")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "附件类型不符: 声明 " + kind + "，实际 " + mime + "（" + meta.getOriginalName() + "）");
        }
    }

    /**
     * 7x（V155）：提交前预估预览（不落库不预扣，前端输入防抖实时询）。返回 estimatedPoints
     * （积分，无价表/模型不可解析记 0）、balance（个人钱包/组池可用）、affordable（est≤0 或余额够）。
     */
    public Map<String, Object> estimatePreview(String kind, String model, Integer videoSeconds,
                                               String resolution, boolean hasReference, Integer imageCount,
                                               Long userId, Long projectGroupId) {
        return estimatePreview(kind, model, videoSeconds, resolution, hasReference, imageCount,
                userId, projectGroupId, null);
    }

    /** HHX-9：+promptChars 版本（context-ir 按提示词长度估 CHAT 价；其余 kind 忽略该参）。 */
    public Map<String, Object> estimatePreview(String kind, String model, Integer videoSeconds,
                                               String resolution, boolean hasReference, Integer imageCount,
                                               Long userId, Long projectGroupId, Integer promptChars) {
        java.math.BigDecimal est = java.math.BigDecimal.ZERO;
        try {
            if ("IMAGE".equalsIgnoreCase(kind)) {
                LlmProviderEntity provider = mediaModelService.resolveImageProviderByModel(model);
                if (provider != null) {
                    est = estimateImagePoints(provider.getId(), model,
                            imageCount == null || imageCount <= 0 ? 1 : imageCount);
                }
            } else if (isContextIrModel(model)) {
                // context-ir：CHAT 估算公式（预览无附件时长，仅提示词长度；提交侧同口径更全）
                LlmProviderEntity provider = mediaModelService.resolveProviderByModel(model);
                if (provider != null) {
                    est = estimateContextIrPoints(provider.getId(), model,
                            promptChars == null ? 0 : promptChars, 0);
                }
            } else {
                LlmProviderEntity provider;
                String resolvedModel;
                if (model == null || model.isBlank()) {
                    // 未指定模型：优先管理员全局默认视频模型，未配置/失效回落默认 provider 首模型
                    String defaultVideoModel = systemSettingService.getDefaultVideoModel();
                    provider = defaultVideoModel == null ? null
                            : mediaModelService.resolveProviderByModel(defaultVideoModel);
                    resolvedModel = provider != null ? defaultVideoModel : null;
                    if (provider == null) {
                        provider = mediaModelService.defaultProvider();
                        resolvedModel = provider == null ? null : mediaModelService.firstModelOf(provider);
                    }
                } else {
                    provider = mediaModelService.resolveProviderByModel(model);
                    resolvedModel = model;
                }
                if (provider != null && resolvedModel != null) {
                    est = estimateVideoPoints(provider.getId(), resolvedModel,
                            videoSeconds, hasReference, resolution);
                }
            }
        } catch (Exception e) {
            log.warn("预估预览失败记0 kind={} model={} : {}", kind, model, e.getMessage());
        }
        java.math.BigDecimal balance = projectGroupId != null
                ? groupWalletService.getGroupBalance(projectGroupId)
                : (userId == null ? java.math.BigDecimal.ZERO : walletService.getBalance(userId));
        // C1（17x-2）：组内预估个人口径——balance 是组池（全组共享），成员还有自己的限额卡，
        // 老预览只看池导致「预估可提交、提交被限额拒」。补 personalScope：限额内可用 + 卡点归因
        // （bindingConstraint=MEMBER 限额卡 / POOL 组池卡 / NONE 都够），前端按卡点分层提示。
        Map<String, Object> personalScope = null;
        boolean affordableMember = true;
        if (projectGroupId != null && userId != null) {
            com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity row =
                    memberMapper.selectByGroupUser(projectGroupId, userId);
            if (row != null) {
                java.math.BigDecimal quota = row.getQuotaLimitPoints();
                java.math.BigDecimal used = row.getUsedPoints() == null
                        ? java.math.BigDecimal.ZERO : row.getUsedPoints();
                // V161（修复III A5）：欠款冻结口径——有未抵扣欠款（组长垫+组池垫）时组内可用=0，
                // 划拨或组长调限额抵清后才恢复；无欠款时可用=max(0, quota−used)（钳 0 防
                // 兜底时代 used>quota 存量行显示负数可用）。名下余额不算「限额内可用」（它是
                // 第二腿资金源，不占限额），单列 selfPoints 供前端展示。
                java.math.BigDecimal debtLeader = nz(row.getDebtLeaderPoints());
                java.math.BigDecimal debtPool = nz(row.getDebtPoolPoints());
                java.math.BigDecimal debtTotal = debtLeader.add(debtPool);
                java.math.BigDecimal avail = null;
                if (quota != null) {
                    avail = quota.subtract(used).max(java.math.BigDecimal.ZERO);
                    // 管理双卡：限额卡之外还有「可分配额度」硬卡（子树预留须保留），取两者更紧者
                    if (com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MANAGER
                            .equals(row.getRole())) {
                        java.math.BigDecimal allocatable = memberBudgetService
                                .allocatable(projectGroupId, row, null);
                        if (allocatable != null && allocatable.compareTo(avail) < 0) {
                            avail = allocatable;
                        }
                    }
                }
                boolean debtFrozen = debtTotal.signum() > 0;
                if (debtFrozen) {
                    avail = java.math.BigDecimal.ZERO;
                }
                affordableMember = !debtFrozen && (avail == null || avail.compareTo(est) >= 0);
                // 卡点归因：DEBT 欠款冻结（先于此层展示）> MEMBER 限额卡 > POOL 组池卡 > NONE
                String bindingConstraint = debtFrozen ? "DEBT"
                        : !affordableMember ? "MEMBER"
                        : balance.compareTo(est) < 0 ? "POOL" : "NONE";
                personalScope = new LinkedHashMap<>();
                personalScope.put("quota", quota);
                personalScope.put("used", used);
                personalScope.put("inProjectAvailable", avail);
                personalScope.put("selfPoints", nz(row.getSelfPoints()));
                personalScope.put("debtPoolPoints", debtPool);
                personalScope.put("debtLeaderPoints", debtLeader);
                personalScope.put("debtTotalPoints", debtTotal);
                personalScope.put("affordableMember", affordableMember);
                personalScope.put("bindingConstraint", bindingConstraint);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("estimatedPoints", est);
        out.put("balance", balance);
        // 2026-08-25 fail-closed：est≤0 提交侧必拒（PRICING_NOT_FOUND），预览同步显示不可提交
        // C1：组内场景同时要求 限额内可用（无限额视为可用）+ 组池够
        boolean poolOk = est.signum() > 0 && balance.compareTo(est) >= 0;
        out.put("affordable", personalScope == null ? poolOk : (affordableMember && poolOk));
        if (personalScope != null) {
            out.put("personalScope", personalScope);
        }
        return out;
    }

    /**
     * 计划5 Step5：视频提交期估价（积分口径）。走
     * {@link com.superprogrammer.billing.service.PricingService#estimateVideoYuan}：
     * SECOND 模式 = 分辨率秒价 × duration；TOKEN 模式 = 价表 est_yuan_per_second × duration。
     * 2026-08-25 fail-closed：价表缺/估价字段未配 <b>抛 PRICING_NOT_FOUND</b>（不再静默记 0——
     * 估价 0 会跳过预检与预扣，余额不足的用户可无限白嫖生成）。预估预览 caller 自行 catch 记 0。
     */
    private java.math.BigDecimal estimateVideoPoints(Long providerId, String model,
                                                     Integer duration, boolean hasRefVideo,
                                                     String resolution) {
        java.math.BigDecimal yuan = pricingService.estimateVideoYuan(
                providerId, model, duration == null ? 0 : duration, resolution, hasRefVideo);
        return pointsRatioService.toPoints(yuan);
    }

    /**
     * 计划5 Step5：图片提交期估价（积分口径，按 maxImages 张数）。
     * 2026-08-25 fail-closed：无价表行抛 PRICING_NOT_FOUND（同视频口径，防估 0 白嫖）。
     */
    private java.math.BigDecimal estimateImagePoints(Long providerId, String model, int imageCount) {
        java.math.BigDecimal yuan = pricingService.computeCost(
                com.superprogrammer.billing.entity.LlmUsageLogEntity.KIND_IMAGE,
                providerId, model, null, null, null, imageCount, false);
        return pointsRatioService.toPoints(yuan);
    }

    // ---------- HHX-9/10：附属任务（context-ir / regeneration）提交期分流 ----------

    /** 平台模型 id 后缀判定（与 MinimaxVideoProvider 同口径；service 层不依赖 provider 类）。 */
    private static boolean isContextIrModel(String model) {
        return model != null && model.trim().toLowerCase().endsWith("-context-ir");
    }

    private static boolean isRegenerationModel(String model) {
        return model != null && model.trim().toLowerCase().endsWith("-regeneration");
    }

    /**
     * HHX-10：再生成源任务校验链（逐条 400/403/404 拒）：
     * 缺参 → 不存在 → 非本人（admin/系统旁路）→ 未成功 → 非同 provider → 源是附属任务
     * （只许对生成结果再生成，链式再生成不做）→ 超 7 天窗口（上游查询/再生成时效同口径）。
     */
    private MediaGenTask requireRegenerationSource(Long sourceTaskId, LlmProviderEntity provider,
                                                   Long userId, boolean admin) {
        if (sourceTaskId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "再生成须提供源任务（sourceTaskId）");
        }
        MediaGenTask source = taskMapper.selectById(sourceTaskId);
        if (source == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "源任务不存在: " + sourceTaskId);
        }
        if (!admin && userId != null && !userId.equals(source.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权对该源任务再生成: " + sourceTaskId);
        }
        if (!MediaGenTask.STATUS_SUCCEEDED.equals(source.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "源任务未成功（当前 " + source.getStatus() + "），仅成功任务可 2K 再生成");
        }
        if (!provider.getId().equals(source.getProviderId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "源任务非本供应商生成，不能再生成");
        }
        if (isContextIrModel(source.getModel()) || isRegenerationModel(source.getModel())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅视频生成任务可再生成（提示词增强/再生成任务不可）");
        }
        if (source.getArkTaskId() == null || source.getArkTaskId().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "源任务缺少上游任务号，无法再生成");
        }
        if (source.getCreatedAt() == null
                || source.getCreatedAt().isBefore(java.time.OffsetDateTime.now().minusDays(7))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "源任务已超 7 天再生成窗口");
        }
        return source;
    }

    /** HHX-10：继承源任务时长（requestConfig.duration；缺失兜底 5 秒——仅估价用，出站不传时长）。 */
    private int regenerationDurationOf(MediaGenTask source) {
        try {
            JsonNode d = objectMapper.readTree(source.getRequestConfig()).path("duration");
            if (d.isNumber() && d.asInt() > 0) {
                return d.asInt();
            }
        } catch (Exception e) {
            log.warn("解析源任务时长失败按 5s 计 taskId={}: {}", source.getId(), e.getMessage());
        }
        return 5;
    }

    /**
     * HHX-9：Context-IR 提交期估价（CHAT 价表行 5.8/23 ¥每百万）。
     * 估算口径（spec §5）：in = 提示词字符×0.75 + 图×1500（视频/音频附件时长提交期不可得按 0，
     * 低估不吞钱——结算按上游 prompt/completion 实测 token 多退少补）；out = 4000 固定。
     */
    private java.math.BigDecimal estimateContextIrPoints(Long providerId, String model, String prompt,
                                                         List<AttachmentRef> attachments) {
        int images = attachments == null ? 0 : (int) attachments.stream()
                .filter(a -> a.getKind() != null && "image".equalsIgnoreCase(a.getKind().trim()))
                .count();
        return estimateContextIrPoints(providerId, model,
                prompt == null ? 0 : prompt.length(), images);
    }

    private java.math.BigDecimal estimateContextIrPoints(Long providerId, String model,
                                                         int promptChars, int imageCount) {
        int in = (int) Math.ceil(promptChars * 0.75) + imageCount * 1500;
        java.math.BigDecimal yuan = pricingService.computeCost(
                com.superprogrammer.billing.entity.LlmUsageLogEntity.KIND_CHAT,
                providerId, model, in, CONTEXT_IR_EST_OUT_TOKENS, null, null, false);
        return pointsRatioService.toPoints(yuan);
    }

    /** V161（修复III A5）：null→0（成员行三新列老行可能 NULL 视图读出前的防御）。 */
    private static java.math.BigDecimal nz(java.math.BigDecimal v) {
        return v == null ? java.math.BigDecimal.ZERO : v;
    }

    private String toJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("requestConfig 序列化失败", e);
        }
    }
}
