import type { WorkflowItem } from './types'

export const workflows: WorkflowItem[] = [
  { id: 'w1', name: '雨夜霓虹 · 主流程', status: 'running', nodeCount: 12, lastRun: '10 分钟前', duration: '2m 41s', owner: '阿鹏' },
  { id: 'w2', name: '发布会开场 8s', status: 'success', nodeCount: 7, lastRun: '1 小时前', duration: '1m 05s', owner: '阿鹏' },
  { id: 'w3', name: '口播短视频流水线', status: 'success', nodeCount: 9, lastRun: '3 小时前', duration: '3m 12s', owner: '小鹿' },
  { id: 'w4', name: '产品功能演示', status: 'draft', nodeCount: 4, lastRun: '—', duration: '—', owner: '小鹿' },
  { id: 'w5', name: '品牌片头模板', status: 'failed', nodeCount: 6, lastRun: '昨天 22:14', duration: '48s', owner: '阿鹏' },
  { id: 'w6', name: '多语言字幕批量', status: 'success', nodeCount: 5, lastRun: '昨天 20:02', duration: '5m 30s', owner: '老周' },
  { id: 'w7', name: '配乐情绪曲线', status: 'draft', nodeCount: 3, lastRun: '—', duration: '—', owner: '老周' },
  { id: 'w8', name: '关键帧风格统一', status: 'running', nodeCount: 8, lastRun: '进行中', duration: '—', owner: '阿鹏' },
  { id: 'w9', name: '社媒竖屏裁切', status: 'success', nodeCount: 4, lastRun: '2 天前', duration: '1m 40s', owner: '小鹿' },
  { id: 'w10', name: '预告片混剪实验', status: 'draft', nodeCount: 11, lastRun: '—', duration: '—', owner: '老周' }
]
