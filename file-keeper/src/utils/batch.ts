import type { FileItem } from '../types/file'

/**
 * Check if batch open is allowed
 */
export function canBatchOpen(files: FileItem[]): boolean {
  return files.length > 0
}

/**
 * Check if batch delete is allowed
 */
export function canBatchDelete(files: FileItem[]): boolean {
  return files.length > 0
}

/**
 * Check if batch move is allowed
 */
export function canBatchMove(files: FileItem[]): boolean {
  return files.length > 0
}
