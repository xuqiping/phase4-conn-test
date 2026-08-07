-- ============================================================
-- V64: seedance provider 的 Cdance2.0 能力配置注入
--
-- 背景：MediaModelCapabilityService 三层合并（保守兜底 → 前缀默认 → provider config 覆盖）。
--   ctaigw 网关的视频模型别名为 Cdance2.0，不命中内置前缀默认（id 需含 seedance-2），
--   若 provider config 列为空 → 走保守兜底（maxImages=1），多模态参考图/视频/音频上传区
--   被前端 capability.maxImages>0 判定缩成 1 张，与 SeedDance 2.0 真实能力（9图/3视频/3音频）不符。
--
-- 本迁移给 models 含 Cdance2.0 的 VIDEO provider 注入 capabilities.Cdance2.0 全量能力，
-- 使其与内置 seedance-2 默认一致。幂等且非破坏：用 jsonb || 逐层合并——保留 config 其他键
--   与 capabilities 下其他模型条目，只补/覆盖 Cdance2.0；重复执行落同值；无匹配 provider 时 0 行。
--   （注：PG16 jsonb_set 不创建中间路径键，故不走 jsonb_set，改 || 合并。）
--
-- 切换环境 / 部署均自动执行（Flyway 启动迁移）。
-- ============================================================

UPDATE llm_providers
SET config = (
      COALESCE(NULLIF(config, '')::jsonb, '{}'::jsonb)
      || jsonb_build_object(
           'capabilities',
           COALESCE((NULLIF(config, '')::jsonb)->'capabilities', '{}'::jsonb)
             || jsonb_build_object(
                  'Cdance2.0',
                  '{"maxImages":9,"maxVideos":3,"maxAudios":3,"maxAttachments":12,"supportsGenerateAudio":true,"videoDataUri":true}'::jsonb
                )
         )
    )::text
WHERE category = 'VIDEO'
  AND models ILIKE '%Cdance2.0%';
