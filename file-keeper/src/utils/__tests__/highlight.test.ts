// src/utils/__tests__/highlight.test.ts
import { describe, it, expect } from 'vitest'
import { highlightText } from '../highlight'

describe('highlightText', () => {
  it('should wrap matching text in mark tags', () => {
    const result = highlightText('hello world', 'world')
    expect(result).toContain('<mark')
    expect(result).toContain('world')
    expect(result).toContain('hello')
  })

  it('should highlight case-insensitively', () => {
    const result = highlightText('Hello World', 'hello')
    expect(result).toContain('<mark')
    expect(result).toContain('Hello')
  })

  it('should return safe text when query is empty', () => {
    const result = highlightText('hello world', '')
    expect(result).toBe('hello world')
    expect(result).not.toContain('<mark')
  })

  it('should escape HTML special characters in text', () => {
    const result = highlightText('<script>alert("xss")</script>', 'script')
    expect(result).not.toContain('<script>')
    expect(result).toContain('&lt;')
    expect(result).toContain('&gt;')
  })

  it('should escape regex special characters in query', () => {
    const result = highlightText('hello (world)', '(world)')
    expect(result).toContain('<mark')
    expect(result).toContain('(world)')
  })

  it('should return text unchanged when query has no matches', () => {
    const result = highlightText('hello world', 'xyz')
    expect(result).toBe('hello world')
  })
})
