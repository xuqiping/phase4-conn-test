import { normalizeShortcut } from '../utils/shortcut'

export interface ShortcutRegistrationAdapter {
  register(shortcut: string, handler: () => void): Promise<void>
  unregister(shortcut: string): Promise<void>
}

export interface FavoriteShortcutFailure {
  id: string
  shortcut: string
  error: string
}

export async function replaceShortcutRegistration(
  adapter: ShortcutRegistrationAdapter,
  oldShortcut: string | undefined,
  newShortcut: string | undefined,
  handler: () => void
): Promise<string> {
  const oldNormalized = normalizeShortcut(oldShortcut ?? '')
  const newNormalized = normalizeShortcut(newShortcut ?? '')
  if (oldNormalized === newNormalized) return newNormalized

  if (!newNormalized) {
    if (oldNormalized) await adapter.unregister(oldNormalized)
    return ''
  }

  await adapter.register(newNormalized, handler)
  if (oldNormalized) {
    try {
      await adapter.unregister(oldNormalized)
    } catch (error) {
      try {
        await adapter.unregister(newNormalized)
      } catch {
        // Keep the original unregister error because it explains why the old value remains.
      }
      throw error
    }
  }
  return newNormalized
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export function createFavoriteShortcutCoordinator(adapter: ShortcutRegistrationAdapter) {
  const registered = new Map<string, string>()

  return {
    async replace(
      id: string,
      oldShortcut: string | undefined,
      newShortcut: string | undefined,
      handler: () => void
    ): Promise<string> {
      const oldNormalized = normalizeShortcut(oldShortcut ?? '')
      const newNormalized = normalizeShortcut(newShortcut ?? '')
      if (oldNormalized === newNormalized) {
        if (newNormalized && registered.get(id) !== newNormalized) {
          await adapter.register(newNormalized, handler)
          registered.set(id, newNormalized)
        }
        return newNormalized
      }

      const replaced = await replaceShortcutRegistration(
        adapter,
        oldNormalized,
        newNormalized,
        handler
      )
      if (!replaced) {
        registered.delete(id)
        return ''
      }
      registered.set(id, replaced)
      return replaced
    },
    async restore(
      items: Array<{ id: string; shortcut?: string }>,
      handlerFor: (id: string) => () => void,
      concurrency = 4
    ): Promise<FavoriteShortcutFailure[]> {
      const queue = items
        .map(item => ({ ...item, shortcut: normalizeShortcut(item.shortcut ?? '') }))
        .filter((item): item is { id: string; shortcut: string } => Boolean(item.shortcut))
      const failures: FavoriteShortcutFailure[] = []
      let nextIndex = 0

      async function worker() {
        while (nextIndex < queue.length) {
          const item = queue[nextIndex++]
          try {
            await adapter.register(item.shortcut, handlerFor(item.id))
            registered.set(item.id, item.shortcut)
          } catch (error) {
            failures.push({ id: item.id, shortcut: item.shortcut, error: errorMessage(error) })
          }
        }
      }

      const workerCount = Math.max(1, Math.min(concurrency, queue.length || 1))
      await Promise.all(Array.from({ length: workerCount }, () => worker()))
      return failures.sort((left, right) => left.id.localeCompare(right.id))
    },
    async unregister(id: string, fallbackShortcut?: string): Promise<void> {
      const shortcut = registered.get(id) ?? normalizeShortcut(fallbackShortcut ?? '')
      if (!shortcut) return
      await adapter.unregister(shortcut)
      registered.delete(id)
    },
    async dispose(): Promise<FavoriteShortcutFailure[]> {
      const failures: FavoriteShortcutFailure[] = []
      for (const [id, shortcut] of registered) {
        try {
          await adapter.unregister(shortcut)
        } catch (error) {
          failures.push({ id, shortcut, error: errorMessage(error) })
        }
      }
      registered.clear()
      return failures
    }
  }
}
