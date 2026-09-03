export type ClipboardKind =
  | 'text'
  | 'html'
  | 'image'
  | 'file'
  | 'url'
  | 'color'
  | 'mixed'
  | 'security_event'

export type ClipboardPasteFormat =
  | 'original'
  | 'plain_text'
  | 'html'
  | 'markdown'
  | 'image_png'
  | 'image_jpeg'
  | 'file_copy'

export type FileExtensionMode = 'allow_all' | 'allow_list' | 'block_list'

export type ClipboardFileSaveMode = 'backup' | 'reference_only'

export type ClipboardDateRangePreset = 'all' | 'today' | 'yesterday' | 'last7Days' | 'last30Days' | 'custom'

export interface ClipboardSourceApp {
  processName: string
  windowTitle: string
  pid?: number
}

export interface ClipboardItemSummary {
  id: string
  kind: ClipboardKind
  title: string
  summary: string
  sourceApp?: ClipboardSourceApp
  createdAt: number
  lastUsedAt?: number
  useCount: number
  isFavorite: boolean
  isPinned: boolean
  pinnedAt?: number
  groupId?: string
  thumbnailPath?: string
  cacheBytes: number
  cacheState: 'none' | 'cached' | 'reference_only' | 'cleaned'
  note?: string
}

export interface ClipboardFileEntry {
  name: string
  originalPath: string
  cachedPath?: string
  sizeBytes: number
  modifiedAt?: number
  hash?: string
  isDirectory: boolean
  copyState: 'cached' | 'reference_only' | 'skipped'
}

export interface ClipboardItemDetail extends ClipboardItemSummary {
  text?: string
  html?: string
  sanitizedHtml?: string
  markdown?: string
  imagePath?: string
  imageWidth?: number
  imageHeight?: number
  imageFormat?: string
  ocrText?: string
  files?: ClipboardFileEntry[]
  url?: string
  urlTitle?: string
  urlDescription?: string
  urlThumbnailPath?: string
  colorHex?: string
  colorRgb?: string
  securityReason?: string
  availableFormats: ClipboardPasteFormat[]
}

export interface ClipboardQuery {
  query?: string
  kind?: ClipboardKind | 'all'
  favoriteOnly?: boolean
  groupId?: string | null
  sourceApp?: string
  startAt?: number
  endAt?: number
  limit: number
  offset: number
}

export interface ClipboardGroup {
  id: string
  name: string
  sortOrder: number
  createdAt: number
  updatedAt: number
}

export interface ClipboardTypeLimitMb {
  image: number
  file: number
  html: number
  linkPreview: number
}

export interface ClipboardSettings {
  monitorEnabled: boolean
  quickPanelShortcut: string
  autoPaste: boolean
  protectSensitiveContent: boolean
  enableOcr: boolean
  enableLinkPreview: boolean
  totalNonTextLimitMb: number
  itemSizeLimitMb: number
  typeLimitsMb: ClipboardTypeLimitMb
  fileSaveMode: ClipboardFileSaveMode
  backupDirectory: string | null
  fileExtensionMode: FileExtensionMode
  fileExtensions: string[]
  excludedApps: string[]
}

export interface ClipboardStorageTypeUsage {
  kind: Exclude<ClipboardKind, 'security_event' | 'mixed'> | 'linkPreview'
  bytes: number
  limitBytes?: number
}

export interface ClipboardStorageUsage {
  totalBytes: number
  limitBytes: number
  byType: ClipboardStorageTypeUsage[]
}
