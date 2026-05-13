// Global shortcut management API
import { register, unregister } from '@tauri-apps/plugin-global-shortcut'

/**
 * Register a global keyboard shortcut
 * @param shortcut - Shortcut string (e.g., "CommandOrControl+Shift+F")
 * @param handler - Callback function to execute when shortcut is triggered
 */
export async function registerGlobalShortcut(
  shortcut: string,
  handler: () => void
): Promise<void> {
  try {
    await register(shortcut, handler)
  } catch (error) {
    console.error('Failed to register shortcut:', error)
    throw error
  }
}

/**
 * Unregister a previously registered global shortcut
 * @param shortcut - Shortcut string to unregister
 */
export async function unregisterGlobalShortcut(shortcut: string): Promise<void> {
  try {
    await unregister(shortcut)
  } catch (error) {
    console.error('Failed to unregister shortcut:', error)
    throw error
  }
}
