import type { AgentItem } from './types'

export const agents: AgentItem[] = [
  { id: 'a1', name: '分镜大师', desc: '把剧本拆成可拍摄的分镜序列，自动标注运镜与时长', tags: ['视频', '分镜'], runs: 12840, rating: 4.8 },
  { id: 'a2', name: '提示词工匠', desc: '优化文生图/文生视频提示词，支持中英文互译润色', tags: ['提示词', '文本'], runs: 31020, rating: 4.9 },
  { id: 'a3', name: '配音导演', desc: '按角色情绪生成配音方案，可选音色与语速曲线', tags: ['音频'], runs: 6530, rating: 4.6 },
  { id: 'a4', name: '镜头剪辑师', desc: '自动粗剪：按节奏点拼接镜头并给出取舍理由', tags: ['视频', '剪辑'], runs: 8920, rating: 4.7 },
  { id: 'a5', name: '风格侦探', desc: '上传参考片，提取色彩/构图/运动风格并生成风格卡', tags: ['图像', '风格'], runs: 4210, rating: 4.5 },
  { id: 'a6', name: '字幕排版工', desc: '生成带样式的字幕轨，支持品牌字体与安全区检测', tags: ['文本', '字幕'], runs: 15600, rating: 4.4 },
  { id: 'a7', name: '配乐猎手', desc: '按情绪曲线匹配免版税配乐，输出音量包络建议', tags: ['音频', '配乐'], runs: 3380, rating: 4.3 },
  { id: 'a8', name: '海报速写', desc: '从视频关键帧一键生成宣发海报草图', tags: ['图像', '海报'], runs: 9850, rating: 4.6 },
  { id: 'a9', name: '脚本医生', desc: '体检脚本：节奏拖沓检测、台词自然度评分', tags: ['脚本', '文本'], runs: 5420, rating: 4.7 },
  { id: 'a10', name: '色彩管家', desc: '统一多镜头色调，输出 LUT 建议与匹配报告', tags: ['视频', '调色'], runs: 2760, rating: 4.5 },
  { id: 'a11', name: '口播教练', desc: '把文稿转成口播提词器节奏，标注停顿与重音', tags: ['音频', '文本'], runs: 7140, rating: 4.2 },
  { id: 'a12', name: '合规哨兵', desc: '扫描成片敏感元素与版权风险，出整改清单', tags: ['审核'], runs: 1930, rating: 4.8 }
]

/** 全部标签（筛选行用） */
export const ALL_TAGS = [...new Set(agents.flatMap((a) => a.tags))]
