import { describe, expect, it } from 'vitest'
import { parseImageRestore, type ImageTaskDetailLike } from './imageGenParams'
import type { ImageModelVO } from '@/api/media'

function model(modelId: string, over: Partial<ImageModelVO['capability']> = {}): ImageModelVO {
  return {
    modelId,
    displayName: modelId,
    providerName: 'p',
    capability: {
      refImageMax: 10,
      refImageFormats: ['jpeg', 'png'],
      sizePresets: ['1K', '2K'],
      supportsWhSize: true,
      supportsSequential: false,
      maxSequentialImages: 1,
      supportsWebSearch: false,
      supportsStream: false,
      outputFormats: ['jpeg', 'png'],
      optimizeModes: ['standard', 'fast'],
      supportsGuidanceScale: true,
      guidanceMin: 1,
      guidanceMax: 10,
      watermarkDefault: true,
      ...over
    }
  }
}

const MODELS = [
  model('seedream-pro'),
  model('seedream-lite', {
    refImageMax: 14,
    sizePresets: ['2K', '3K', '4K'],
    supportsSequential: true,
    maxSequentialImages: 15,
    supportsWebSearch: true,
    supportsGuidanceScale: false,
    optimizeModes: ['standard']
  })
]

function task(model: string | null, submittedRequest: Record<string, unknown> | null): ImageTaskDetailLike {
  return { model, submittedRequest }
}

describe('parseImageRestore', () => {
  it('submittedRequest=null → 返回 null（旧任务/无归属跳过还原）', () => {
    expect(parseImageRestore(task('seedream-pro', null), MODELS)).toBeNull()
  })

  it('完整参数 → 全字段还原', () => {
    const patch = parseImageRestore(task('seedream-pro', {
      prompt: '赛博猫', size: '2K', outputFormat: 'png', watermark: false,
      guidanceScale: 7, optimizeMode: 'fast', refFileIds: ['f1', 'f2']
    }), MODELS)
    expect(patch).not.toBeNull()
    expect(patch!.model).toBe('seedream-pro')
    expect(patch!.prompt).toBe('赛博猫')
    expect(patch!.size).toBe('2K')
    expect(patch!.customSize).toBe('')
    expect(patch!.outputFormat).toBe('png')
    expect(patch!.optimizeMode).toBe('fast')
    expect(patch!.guidanceScale).toBe(7)
    expect(patch!.watermark).toBe(false)
    expect(patch!.refFileIds).toEqual(['f1', 'f2'])
    expect(patch!.warnings).toEqual([])
  })

  it('自定义 WxH（支持自定义模型）→ __custom__ + customSize 回填', () => {
    const patch = parseImageRestore(task('seedream-pro', { prompt: 'p', size: '1536x1024' }), MODELS)
    expect(patch!.size).toBe('__custom__')
    expect(patch!.customSize).toBe('1536x1024')
  })

  it('非预设尺寸 + 不支持自定义的模型 → 回退首预设 + 告警', () => {
    const rigid = model('rigid', { supportsWhSize: false, sizePresets: ['1K'] })
    const patch = parseImageRestore(task('rigid', { prompt: 'p', size: '1536x1024' }), [rigid])
    expect(patch!.size).toBe('1K')
    expect(patch!.customSize).toBe('')
    expect(patch!.warnings.some(w => w.includes('1536x1024'))).toBe(true)
  })

  it('模型已下线 → 值保真回填 + 下线告警（cap 默认兜底）', () => {
    const patch = parseImageRestore(task('ghost-model', {
      prompt: 'p', size: '9K', watermark: false, refFileIds: ['f1']
    }), MODELS)
    expect(patch!.model).toBe('ghost-model')
    expect(patch!.size).toBe('9K') // 无 cap 原样回填，表单隐藏不展示
    expect(patch!.watermark).toBe(false)
    expect(patch!.refFileIds).toEqual(['f1'])
    expect(patch!.warnings.some(w => w.includes('已下线'))).toBe(true)
  })

  it('参考图超当前模型 cap → 截断 + 告警', () => {
    const refs = Array.from({ length: 12 }, (_, i) => `f${i}`)
    const patch = parseImageRestore(task('seedream-pro', { prompt: 'p', refFileIds: refs }), MODELS)
    expect(patch!.refFileIds).toHaveLength(10)
    expect(patch!.warnings.some(w => w.includes('截断'))).toBe(true)
  })

  it('缺省字段回能力默认值（组图/联网/水印/优化模式）', () => {
    const patch = parseImageRestore(task('seedream-lite', { prompt: 'p' }), MODELS)
    expect(patch!.size).toBe('2K')
    expect(patch!.sequential).toBe('disabled')
    expect(patch!.webSearch).toBe(false)
    expect(patch!.watermark).toBe(true) // cap.watermarkDefault
    expect(patch!.optimizeMode).toBe('standard')
    expect(patch!.maxImages).toBe(4)
    expect(patch!.refFileIds).toEqual([])
  })

  it('lite 组图任务 → sequential/maxImages 还原，张数输入框随 sequential=auto 显示', () => {
    const patch = parseImageRestore(task('seedream-lite', {
      prompt: 'p', sequential: 'auto', maxImages: 6, webSearch: true
    }), MODELS)
    expect(patch!.sequential).toBe('auto')
    expect(patch!.maxImages).toBe(6)
    expect(patch!.webSearch).toBe(true)
  })

  it('refFileIds 非数组/含非字符串 → 过滤为空数组', () => {
    const patch = parseImageRestore(task('seedream-pro', {
      prompt: 'p', refFileIds: ['ok', 1, null, '']
    }), MODELS)
    expect(patch!.refFileIds).toEqual(['ok'])
  })
})
