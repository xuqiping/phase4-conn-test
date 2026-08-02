import { describe, it, expect } from 'vitest'
import { getVisibleProcessColumns, reorderColumns } from '../processColumns'
import type { ColumnConfig } from '../../types/process'

function createColumns(): ColumnConfig[] {
  return [
    { key: 'name', label: 'Name', width: '200px', visible: true, sortable: true },
    { key: 'category', label: 'Category', width: '120px', visible: true, sortable: true },
    { key: 'pid', label: 'PID', width: '80px', visible: true, sortable: true },
    { key: 'memory', label: 'Memory', width: '100px', visible: true, sortable: true },
    { key: 'cpu', label: 'CPU', width: '80px', visible: false, sortable: true },
    { key: 'windowTitle', label: 'Window Title', width: '200px', visible: true, sortable: false }
  ]
}

describe('processColumns helpers', () => {
  it('returns visible process columns in settings order', () => {
    const columns = createColumns()

    const reordered = reorderColumns(columns, 5, 1)
    const visibleKeys = getVisibleProcessColumns(reordered).map(column => column.key)

    expect(visibleKeys).toEqual(['name', 'windowTitle', 'category', 'pid', 'memory'])
  })

  it('moves a column to the new index without mutating the original array', () => {
    const columns = createColumns()

    const reordered = reorderColumns(columns, 3, 0)

    expect(reordered.map(column => column.key)).toEqual([
      'memory',
      'name',
      'category',
      'pid',
      'cpu',
      'windowTitle'
    ])
    expect(columns.map(column => column.key)).toEqual([
      'name',
      'category',
      'pid',
      'memory',
      'cpu',
      'windowTitle'
    ])
  })
})
