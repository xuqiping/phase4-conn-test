// ============================================================
// 高山流水 · 模块场景注册表（DESIGN-TOKEN-0002 §1/§3 真值源）
// 每模块：专属色 RGB + 诗签 + 山形种子（四档轮转，构图源异）
// ============================================================

export type SceneKey =
  | 'chat' | 'knowledge' | 'video-gen' | 'image-gen' | 'video-edit'
  | 'canvas' | 'assets' | 'wallet' | 'project-groups' | 'feedback'
  | 'settings' | 'admin'

export interface ModuleScene {
  /** 场景色 RGB 三元组（rgba 调透明度用） */
  rgb: string
  /** 右缘竖排诗签（≤8 字，真值表禁现场发挥） */
  poem: string
  /** 山形种子 1-4：双峰/孤峰/三岭/平远水岸 */
  ridgeSeed: 1 | 2 | 3 | 4
}

export const MODULE_SCENES: Record<SceneKey, ModuleScene> = {
  chat:             { rgb: '143,188,212', poem: '高山流水，静候知音',   ridgeSeed: 2 }, // 天青·流水知音
  knowledge:        { rgb: '78,127,166',  poem: '问渠那得清如许',       ridgeSeed: 3 }, // 石青·源头活水
  'video-gen':      { rgb: '138,128,163', poem: '行云流影，山川入画',   ridgeSeed: 1 }, // 暮山紫·流光
  'image-gen':      { rgb: '124,181,164', poem: '远山如黛，近水含烟',   ridgeSeed: 4 }, // 天水碧·画境
  'video-edit':     { rgb: '58,95,125',   poem: '裁云为衣，剪霞作幕',   ridgeSeed: 2 }, // 深石青·剪裁
  canvas:           { rgb: '154,171,188', poem: '空山新雨后',           ridgeSeed: 4 }, // 月白·留白
  assets:           { rgb: '53,104,127',  poem: '海纳百川，有容乃大',   ridgeSeed: 3 }, // 黛蓝·藏珍阁
  wallet:           { rgb: '99,185,154',  poem: '清泉石上流',           ridgeSeed: 4 }, // 青碧·活水
  'project-groups': { rgb: '110,160,138', poem: '群贤毕至，少长咸集',   ridgeSeed: 1 }, // 雅集青
  feedback:         { rgb: '169,159,189', poem: '空谷传声，虚堂习听',   ridgeSeed: 2 }, // 暮山紫浅·传音
  settings:         { rgb: '155,170,188', poem: '心远地自偏',           ridgeSeed: 4 }, // 月白灰·静室
  admin:            { rgb: '110,150,190', poem: '居高声自远',           ridgeSeed: 3 }  // 星野蓝·观星台
}

/** 山形种子 → 椭圆渐变参数（近山/远山两层，底部 20% 区域内） */
export function ridgeGradients(seed: 1 | 2 | 3 | 4, rgb: string, aNear: number, aFar: number): string[] {
  switch (seed) {
    case 1: // 双峰对峙
      return [
        `radial-gradient(ellipse 42% 60% at 28% 108%, rgba(${rgb}, ${aNear}) 0%, transparent 70%)`,
        `radial-gradient(ellipse 36% 48% at 72% 112%, rgba(${rgb}, ${aFar}) 0%, transparent 70%)`
      ]
    case 2: // 孤峰偏左
      return [
        `radial-gradient(ellipse 50% 66% at 22% 112%, rgba(${rgb}, ${aNear}) 0%, transparent 70%)`,
        `radial-gradient(ellipse 70% 36% at 68% 118%, rgba(${rgb}, ${aFar}) 0%, transparent 70%)`
      ]
    case 3: // 连绵三岭
      return [
        `radial-gradient(ellipse 30% 52% at 15% 110%, rgba(${rgb}, ${aNear}) 0%, transparent 70%)`,
        `radial-gradient(ellipse 34% 44% at 50% 114%, rgba(${rgb}, ${aFar}) 0%, transparent 70%)`,
        `radial-gradient(ellipse 28% 40% at 85% 112%, rgba(${rgb}, ${aNear * 0.7}) 0%, transparent 70%)`
      ]
    case 4: // 平远水岸
    default:
      return [
        `radial-gradient(ellipse 90% 34% at 50% 116%, rgba(${rgb}, ${aNear}) 0%, transparent 70%)`,
        `radial-gradient(ellipse 60% 22% at 30% 120%, rgba(${rgb}, ${aFar}) 0%, transparent 70%)`
      ]
  }
}
