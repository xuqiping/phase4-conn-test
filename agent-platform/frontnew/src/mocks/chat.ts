import type { ChatSession } from './types'

export const chatSessions: ChatSession[] = [
  {
    id: 's1',
    title: '赛博朋克短片创意',
    messages: [
      { id: 'm1', role: 'user', kind: 'text', content: '帮我想一个 30 秒赛博朋克短片的创意方向', time: '10:02' },
      { id: 'm2', role: 'ai', kind: 'text', content: '推荐「雨夜霓虹」方向：孤独骑手穿越霓虹街区，用 3 个镜头完成起承转合。冷蓝主调 + 品红点缀，适合当前模型擅长的光影表现。', time: '10:02' },
      { id: 'm3', role: 'user', kind: 'text', content: '给我镜头一的提示词框架', time: '10:03' },
      { id: 'm4', role: 'ai', kind: 'code', content: 'Neon-lit rainy street, lone rider on motorcycle,\ncinematic lighting, 35mm anamorphic lens,\nshallow depth of field, cyan-magenta palette', time: '10:03' },
      { id: 'm5', role: 'ai', kind: 'quote', content: '镜头一关键帧 · 雨夜霓虹 · v2', quoteFrom: '资产库', time: '10:03' },
      { id: 'm6', role: 'ai', kind: 'text', content: '上面这段提示词可直接拖入画布生成关键帧，引用卡来自资产库上次生成的结果。', time: '10:04' }
    ]
  },
  {
    id: 's2',
    title: '产品发布会开场视频',
    messages: [
      { id: 'm7', role: 'user', kind: 'text', content: '发布会开场需要 8 秒大气镜头', time: '昨天 18:40' },
      { id: 'm8', role: 'ai', kind: 'text', content: '建议用俯冲穿越云层的运动镜头，配合低音铺底。要先出分镜还是直接试一版？', time: '昨天 18:41' }
    ]
  }
]

/** mock 流式回复的预设文本 */
export const MOCK_REPLY =
  '收到。基于当前画布上下文，我建议先锁定分镜结构再生成关键帧——这样视频合成阶段的一致性会显著更好。需要我按这个思路出一版分镜草案吗？'
