// src/utils/__tests__/file.test.ts
import { describe, it, expect } from 'vitest'
import { deriveIconFromExt, resolveGroupId } from '../file'

describe('deriveIconFromExt', () => {
  it('should return "word" for .doc and .docx files', () => {
    expect(deriveIconFromExt('report.doc')).toBe('word')
    expect(deriveIconFromExt('report.docx')).toBe('word')
  })

  it('should return "excel" for .xls and .xlsx files', () => {
    expect(deriveIconFromExt('budget.xls')).toBe('excel')
    expect(deriveIconFromExt('budget.xlsx')).toBe('excel')
  })

  it('should return "image" for image extensions', () => {
    expect(deriveIconFromExt('photo.png')).toBe('image')
    expect(deriveIconFromExt('photo.jpg')).toBe('image')
    expect(deriveIconFromExt('photo.jpeg')).toBe('image')
    expect(deriveIconFromExt('photo.gif')).toBe('image')
  })

  it('should return "code" for code file extensions', () => {
    expect(deriveIconFromExt('app.js')).toBe('code')
    expect(deriveIconFromExt('app.ts')).toBe('code')
    expect(deriveIconFromExt('app.py')).toBe('code')
    expect(deriveIconFromExt('app.java')).toBe('code')
  })

  it('should return "file" for unknown extensions', () => {
    expect(deriveIconFromExt('readme.md')).toBe('file')
    expect(deriveIconFromExt('archive.zip')).toBe('file')
  })

  it('should handle filenames without extensions', () => {
    expect(deriveIconFromExt('Makefile')).toBe('file')
  })
})

describe('resolveGroupId', () => {
  it('should return custom group id when current is "all"', () => {
    expect(resolveGroupId('all', 'custom-1')).toBe('custom-1')
  })

  it('should return custom group id when current is "recent"', () => {
    expect(resolveGroupId('recent', 'custom-1')).toBe('custom-1')
  })

  it('should return "all" when current is "all" and no custom group', () => {
    expect(resolveGroupId('all', undefined)).toBe('all')
  })

  it('should return current group id when not "all" or "recent"', () => {
    expect(resolveGroupId('custom-2', 'custom-1')).toBe('custom-2')
  })
})
