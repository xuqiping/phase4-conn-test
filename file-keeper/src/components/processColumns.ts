import type { ColumnConfig } from '../types/process'

const supportedProcessColumnKeys = ['name', 'category', 'pid', 'memory', 'cpu', 'windowTitle'] as const
const supportedProcessColumnKeySet = new Set<string>(supportedProcessColumnKeys)
const processColumnClassByKey = {
  name: 'cell-name',
  category: 'cell-category',
  pid: 'cell-pid',
  memory: 'cell-memory',
  cpu: 'cell-cpu',
  windowTitle: 'cell-window-title'
} as const

const defaultProcessColumns: ColumnConfig[] = [
  { key: 'name', label: 'Name', width: '200px', visible: true, sortable: true },
  { key: 'category', label: 'Category', width: '120px', visible: true, sortable: true },
  { key: 'pid', label: 'PID', width: '80px', visible: true, sortable: true },
  { key: 'memory', label: 'Memory', width: '100px', visible: true, sortable: true },
  { key: 'cpu', label: 'CPU', width: '80px', visible: true, sortable: true },
  { key: 'windowTitle', label: 'Window Title', width: '200px', visible: true, sortable: true }
]

export type SupportedProcessColumnKey = typeof supportedProcessColumnKeys[number]
export type VisibleProcessColumn = ColumnConfig & { key: SupportedProcessColumnKey }

export function getDefaultProcessColumns(): ColumnConfig[] {
  return defaultProcessColumns.map(column => ({ ...column }))
}

export function isSupportedProcessColumnKey(key: string): key is SupportedProcessColumnKey {
  return supportedProcessColumnKeySet.has(key)
}

export function getVisibleProcessColumns(columns: ColumnConfig[]): VisibleProcessColumn[] {
  return columns.filter(
    (column): column is VisibleProcessColumn => column.visible && isSupportedProcessColumnKey(column.key)
  )
}

export function getProcessColumnClass(key: SupportedProcessColumnKey): string {
  return processColumnClassByKey[key]
}

export function reorderColumns(columns: ColumnConfig[], oldIndex: number, newIndex: number): ColumnConfig[] {
  if (
    oldIndex === newIndex ||
    oldIndex < 0 ||
    newIndex < 0 ||
    oldIndex >= columns.length ||
    newIndex >= columns.length
  ) {
    return [...columns]
  }

  const reordered = [...columns]
  const [movedColumn] = reordered.splice(oldIndex, 1)
  reordered.splice(newIndex, 0, movedColumn)
  return reordered
}

export function getColumnSettingsSortableOptions(onReorder: (oldIndex: number, newIndex: number) => void) {
  return {
    animation: 150,
    handle: '.drag-handle',
    forceFallback: true,
    fallbackOnBody: true,
    fallbackClass: 'sortable-fallback',
    fallbackTolerance: 3,
    chosenClass: 'sortable-chosen',
    ghostClass: 'sortable-ghost',
    dragClass: 'sortable-drag',
    onEnd: (evt: { oldIndex?: number; newIndex?: number }) => {
      if (evt.oldIndex === undefined || evt.newIndex === undefined) return
      onReorder(evt.oldIndex, evt.newIndex)
    }
  }
}
