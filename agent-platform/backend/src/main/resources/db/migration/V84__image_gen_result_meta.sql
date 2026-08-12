-- ============================================================
-- V84: 图片生成模块 — media_gen_tasks 加 result_meta 列
-- 功能：即梦 Seedream 生图（5.0 lite + pro，plan 生图大模型接入.md Chunk A）
--
-- 设计要点：
--   1. 图片任务一次返 N 张图（1~15），与视频单 url 不同 → 新增 result_meta JSONB
--      存 {imageFileIds[], generatedImages, outputTokens}；视频行留 NULL，零回归。
--   2. task_type 列为 VARCHAR(16) 无枚举 CHECK（V54），新值 TEXT2IMAGE/IMAGE2IMAGE
--      直接可用，无需改类型/不加约束。
--   3. provider 行 + 能力 manifest 不在此 seed：
--      - provider 由 admin 在「全局模型供应商」建（category=IMAGE + 图片端点 + key），
--        与 seedance 视频 provider 同路径（用户自填 key）。
--      - 能力（参考图上限/size 枚举/组图/联网/guidance 等）烤进
--        MediaModelCapabilityService.resolveImage() 的 defaultsFor()，按模型 id
--        含 seedream+lite/pro 子串命中；用户建 provider 填模型 id 即自动匹配，无需手改 config。
--   4. 计费复用 KIND_IMAGE 分支（MediaBillingService.chargeMedia 早已就绪）；
--      pricing_rule 需 admin 配两模型 price_per_image（V66 表无 seed，部署必做）。
-- ============================================================

-- 1. 图片结果元数据列（视频任务不写，恒 NULL）
ALTER TABLE media_gen_tasks ADD COLUMN result_meta JSONB;

COMMENT ON COLUMN media_gen_tasks.result_meta IS
    '图片任务结果元数据 {imageFileIds[],generatedImages,outputTokens}；视频任务 NULL';

-- ============================================================
-- 回滚（rollback）：
-- ALTER TABLE media_gen_tasks DROP COLUMN IF EXISTS result_meta;
-- ============================================================
