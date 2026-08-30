// ============================================================
// 图片生成页 · 历史任务参数还原引擎（问题3：点历史记录还原左侧参数）
//
// 数据源：getTask 详情 VO —— model 在任务实体列，其余提交参数在
// submittedRequest（平台落库的 request_config，已剔除 Provider 快照）。
// 纯函数（无 Vue 依赖），供 ImageGenView.viewHistory 调 + 单测覆盖。
//
// 还原铁律：调用方必须逐项直赋 form，**不得走 onModelChange**
// （它会把其余字段重置成模型默认值，还原被吞）。
// ============================================================

import type { ImageModelCapability, ImageModelVO } from '@/api/media'

/** 详情最小契约（对齐 MediaTaskVO 还原所需子集）。 */
export interface ImageTaskDetailLike {
  model: string | null
  submittedRequest: Record<string, unknown> | null
}

/** 还原产物：逐项对应 ImageGenView.form 字段 + 参考图 + 告警。 */
export interface ImageRestorePatch {
  /** 任务当时模型（可能已下线，仍回填保真） */
  model: string
  prompt: string
  /** size 预设值；自定义 WxH 时为 '__custom__' */
  size: string
  /** 自定义 WxH 原文（size='__custom__' 时非空） */
  customSize: string
  /** C3：比例模式原值（submittedRequest.ratio；非空时 size 为档位、customSize 空） */
  ratio: string
  outputFormat: string
  optimizeMode: string
  guidanceScale: number
  sequential: string
  maxImages: number
  webSearch: boolean
  watermark: boolean
  /** 参考图 fileId 列表（超当前模型 cap 已截断，截断有 warning） */
  refFileIds: string[]
  /** 非阻断告警（模型下线/参考图截断/参数解析异常等），调用方 message.warning 展示 */
  warnings: string[]
}

const CUSTOM_SIZE = '__custom__'

/**
 * 解析历史任务详情 → 表单还原补丁。
 *
 * @param task    getTask 详情（model + submittedRequest）
 * @param models  当前生图模型目录（判模型是否下线 + 提供能力默认值）
 * @returns 补丁；submittedRequest 为 null（旧任务/无归属）时返回 null，调用方跳过还原
 */
export function parseImageRestore(
  task: ImageTaskDetailLike,
  models: ImageModelVO[]
): ImageRestorePatch | null {
  if (!task.submittedRequest) return null
  const cfg = task.submittedRequest
  const warnings: string[] = []
  const model = task.model ?? ''
  const cap: ImageModelCapability | null =
    models.find(m => m.modelId === model)?.capability ?? null
  if (model && !cap) {
    warnings.push(`历史模型 ${model} 已下线，仅可回看参数，不能直接重新提交`)
  }

  // size：命中预设直接选；支持自定义的模型遇非预设值 → __custom__ 回填原文；
  // 不支持自定义的模型遇非预设值 → 回退首个预设 + 告警；模型下线（无能力清单）→ 原样回填（表单隐藏不展示）
  // C3：比例模式任务（ratio 非空）——size 已被后端推导成 WxH，还原为 比例+默认2K 档（原档位未留痕）
  const ratio = str(cfg.ratio)
  const rawSize = str(cfg.size)
  let size = ''
  let customSize = ''
  if (ratio) {
    size = cap?.sizePresets[0] ?? ''
  } else if (rawSize) {
    if (!cap) {
      size = rawSize
    } else if (cap.sizePresets.includes(rawSize)) {
      size = rawSize
    } else if (cap.supportsWhSize) {
      size = CUSTOM_SIZE
      customSize = rawSize
    } else {
      size = cap.sizePresets[0] ?? ''
      warnings.push(`尺寸 ${rawSize} 不被当前模型支持，已回退 ${size}`)
    }
  } else {
    size = cap?.sizePresets[0] ?? ''
  }

  // 参考图：超当前模型 cap 截断（后端提交时会再校验拦截，前端先对齐）
  const rawRefs = strArr(cfg.refFileIds)
  let refFileIds = rawRefs
  if (cap && rawRefs.length > cap.refImageMax) {
    refFileIds = rawRefs.slice(0, cap.refImageMax)
    warnings.push(`参考图 ${rawRefs.length} 张超当前模型上限 ${cap.refImageMax}，已截断`)
  }

  return {
    model,
    prompt: str(cfg.prompt),
    size,
    customSize,
    ratio,
    outputFormat: str(cfg.outputFormat) || cap?.outputFormats[0] || '',
    optimizeMode: str(cfg.optimizeMode) || cap?.optimizeModes[0] || '',
    guidanceScale: num(cfg.guidanceScale,
      cap ? Math.round((cap.guidanceMin + cap.guidanceMax) / 2) : 5),
    sequential: str(cfg.sequential) || (cap?.supportsSequential ? 'disabled' : ''),
    maxImages: num(cfg.maxImages, Math.min(4, cap?.maxSequentialImages || 4)),
    webSearch: bool(cfg.webSearch),
    watermark: cfg.watermark === undefined || cfg.watermark === null
      ? cap?.watermarkDefault ?? true
      : bool(cfg.watermark),
    refFileIds,
    warnings
  }
}

function str(v: unknown): string {
  return typeof v === 'string' ? v : ''
}
function num(v: unknown, fallback: number): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : fallback
}
function bool(v: unknown): boolean {
  return v === true
}
function strArr(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x.length > 0) : []
}
