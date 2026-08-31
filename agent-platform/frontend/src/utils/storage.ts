// ============================================================
// localStorage 封装 — 类型安全的持久化存储
// ============================================================

/**
 * 存储键名常量
 */
export const STORAGE_KEYS = {
  ACCESS_TOKEN: 'access_token',
  REFRESH_TOKEN: 'refresh_token',
  USER_INFO: 'user_info',
  THEME: 'app_theme',
  SIDEBAR_COLLAPSED: 'sidebar_collapsed',
  CHAT_SELECTED_MODEL: 'chat_selected_model',
  CHAT_SELECTED_TARGET: 'chat_selected_target',
  CHAT_RAG_ENABLED: 'chat_rag_enabled',
  CHAT_WEB_SEARCH_ENABLED: 'chat_web_search_enabled',
  /** 修复IX-1：思考强度档位（OFF/STANDARD/DEEP；模型未声明 thinkingLevels 时选择器不显示）。 */
  CHAT_THINKING_LEVEL: 'chat_thinking_level',
  /** 14x-2：智能对话引用知识库（KB 多选，会话级持久化到后端 ChatSession.kbIds）。 */
  CHAT_KB_IDS: 'chat_kb_ids',
  /** 计划5 Step6：五入口「参与项目」选择（null=个人钱包计费；随消息/任务提交传 gid）。 */
  CHAT_PROJECT_GROUP_ID: 'chat_project_group_id',
  KB_ASK_PROJECT_GROUP_ID: 'kb_ask_project_group_id',
  IMAGE_GEN_PROJECT_GROUP_ID: 'image_gen_project_group_id',
  VIDEO_GEN_PROJECT_GROUP_ID: 'video_gen_project_group_id'
} as const

/**
 * 安全地从 localStorage 读取数据
 * @param key 存储键名
 * @returns 解析后的数据，读取失败返回 null
 */
export function getStorage<T>(key: string): T | null {
  try {
    const value = localStorage.getItem(key)
    if (value === null) return null
    return JSON.parse(value) as T
  } catch {
    return null
  }
}

/**
 * 安全地将数据写入 localStorage
 * @param key 存储键名
 * @param value 要存储的数据
 */
export function setStorage<T>(key: string, value: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch (error) {
    console.error('localStorage写入失败:', error)
  }
}

/**
 * 从 localStorage 移除指定键
 * @param key 存储键名
 */
export function removeStorage(key: string): void {
  localStorage.removeItem(key)
}

/**
 * 清除所有认证相关的存储数据
 */
export function clearAuthStorage(): void {
  removeStorage(STORAGE_KEYS.ACCESS_TOKEN)
  removeStorage(STORAGE_KEYS.REFRESH_TOKEN)
  removeStorage(STORAGE_KEYS.USER_INFO)
}
