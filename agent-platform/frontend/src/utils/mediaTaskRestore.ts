import type { InputAttachmentVO } from '@/api/media'
import { uuid } from '@/utils/uuid'

export interface RestoredAttachment {
  id: string
  fileId: string
  name: string
  reusable: boolean
  url?: string
}

export interface RestoredAttachmentBuckets {
  firstFrame: RestoredAttachment | null
  lastFrame: RestoredAttachment | null
  images: RestoredAttachment[]
  videos: RestoredAttachment[]
  audios: RestoredAttachment[]
}

/** 把详情附件摘要转成表单槽位；不读文件，预览与权限复检由页面按需完成。 */
export function bucketRestoredAttachments(
  input: InputAttachmentVO[],
  idFactory: () => string = () => uuid()
): RestoredAttachmentBuckets {
  const result: RestoredAttachmentBuckets = {
    firstFrame: null,
    lastFrame: null,
    images: [],
    videos: [],
    audios: []
  }
  for (const attachment of input) {
    const target = {
      id: idFactory(),
      fileId: attachment.fileId,
      name: attachment.name?.trim() || fallbackName(attachment, result),
      reusable: true
    }
    if (attachment.kind === 'image' && attachment.frameRole === 'first_frame') result.firstFrame = target
    else if (attachment.kind === 'image' && attachment.frameRole === 'last_frame') result.lastFrame = target
    else if (attachment.kind === 'image') result.images.push(target)
    else if (attachment.kind === 'video') result.videos.push(target)
    else if (attachment.kind === 'audio') result.audios.push(target)
  }
  return result
}

function fallbackName(attachment: InputAttachmentVO, result: RestoredAttachmentBuckets): string {
  if (attachment.frameRole === 'first_frame') return '首帧'
  if (attachment.frameRole === 'last_frame') return '尾帧'
  if (attachment.kind === 'image') return `参考图${result.images.length + 1}`
  if (attachment.kind === 'video') return `参考视频${result.videos.length + 1}`
  return `参考音频${result.audios.length + 1}`
}
