export interface InspirationNote {
  id?: number
  content: string
  tags?: string[]
  source?: string
  platformMessageId?: string
  reportConfigIds?: number[]
  reviewedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface InspirationNoteForm {
  content: string
  tags?: string[]
  source?: string
  platformMessageId?: string
  reportConfigIds?: number[]
}
