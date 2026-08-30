import { describe, expect, it } from 'vitest'
import {
  buildSkillConfig,
  buildStepConfig,
  parseLlmStepConfig,
  parseSkillInputParams,
  STEP_ACTION_OPTIONS
} from './skillConfigForm'

describe('skillConfigForm', () => {
  it('parses and builds public input parameter schema', () => {
    const params = parseSkillInputParams(JSON.stringify({
      inputParams: [
        { key: 'summary', label: '摘要', description: '上游摘要', required: true },
        { key: 'testResult', label: '测试结果', type: 'textarea' }
      ]
    }))

    expect(params).toEqual([
      { key: 'summary', label: '摘要', description: '上游摘要', type: 'text', required: true, defaultValue: '' },
      { key: 'testResult', label: '测试结果', description: '', type: 'textarea', required: false, defaultValue: '' }
    ])
    expect(JSON.parse(buildSkillConfig(params))).toEqual({
      inputParams: [
        { key: 'summary', label: '摘要', description: '上游摘要', type: 'text', required: true },
        { key: 'testResult', label: '测试结果', type: 'textarea', required: false }
      ]
    })
  })

  it('parses and builds llm call step config with defaults', () => {
    const form = parseLlmStepConfig(JSON.stringify({
      promptTemplate: '请总结 {{input}}',
      outputKey: 'summary'
    }))

    expect(form).toEqual({
      systemPrompt: '',
      promptTemplate: '请总结 {{input}}',
      model: '',
      temperature: 0.7,
      outputKey: 'summary'
    })
    expect(JSON.parse(buildStepConfig(form))).toMatchObject({
      promptTemplate: '请总结 {{input}}',
      model: '',
      temperature: 0.7,
      outputKey: 'summary'
    })
  })

  it('lists all supported step actions for the dropdown', () => {
    expect(STEP_ACTION_OPTIONS).toEqual([
      expect.objectContaining({
        label: 'LLM 调用',
        value: 'LLM_CALL'
      })
    ])
  })
})
