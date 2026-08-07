-- ============================================================
-- V67: 给 V64 注入的 capabilities.Cdance2.0 补 supportedResolutions（含 4K）
--
-- 背景：V64 给 ctaigw 网关别名 Cdance2.0 注入了多模态能力（9图/3视频/3音频/audio/videoDataUri），
--   但 override JSON 漏了 supportedResolutions 键。applyOverride 对缺失键保留 base 默认，
--   而 Cdance2.0 的 base = 未知模型保守兜底 = RES_UPTO_1080（480p/720p/1080p，无 4K）。
--   → 前端分辨率下拉无 4K 选项，即「Cdance2.0 没了 4K」根因（与 maxRes 默认 720p 双重拦截，见 MediaGenProperties）。
--
-- 本迁移用 jsonb || 重写整个 Cdance2.0 子对象（含 V64 全字段 + supportedResolutions 全梯含 4K），
--   幂等：重复执行落同值；无匹配 provider 时 0 行。不改 config 其他键与 capabilities 下其他模型条目。
--   （注：jsonb || 在 Cdance2.0 键层是整对象替换，故须写全字段，否则会丢 V64 已设的 maxVideos 等。）
--
-- 配套代码侧：MediaGenProperties.maxRes 默认已从 720p 抬到 4K（capability 是逐模型真闸门，
--   maxRes 仅第二道全局兜底；原 720p 连 1080p 都误杀）。
-- ============================================================

UPDATE llm_providers
SET config = (
      COALESCE(NULLIF(config, '')::jsonb, '{}'::jsonb)
      || jsonb_build_object(
           'capabilities',
           COALESCE((NULLIF(config, '')::jsonb)->'capabilities', '{}'::jsonb)
             || jsonb_build_object(
                  'Cdance2.0',
                  '{"maxImages":9,"maxVideos":3,"maxAudios":3,"maxAttachments":12,"supportsGenerateAudio":true,"videoDataUri":true,"supportedResolutions":["480p","720p","1080p","4K"],"supportedRatios":["21:9","16:9","4:3","1:1","3:4","9:16","adaptive"],"minDuration":4,"maxDuration":15}'::jsonb
                )
         )
    )::text
WHERE category = 'VIDEO'
  AND models ILIKE '%Cdance2.0%';
