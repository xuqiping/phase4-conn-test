import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'

vi.mock('@/composables/useLazyFilePreview', () => ({
  useLazyFilePreview: () => ({ url: ref('blob:asset-preview'), failed: ref(false) })
}))

import AssetPickerMediaPreview from './AssetPickerMediaPreview.vue'

describe('AssetPickerMediaPreview', () => {
  it('AC-V3-04 图片和视频按媒体类型渲染预览', () => {
    const image = mount(AssetPickerMediaPreview, { props: { fileId: 'img', mediaType: '图片', name: '图' } })
    const video = mount(AssetPickerMediaPreview, { props: { fileId: 'vid', mediaType: '视频', name: '视频' } })
    expect(image.find('img').attributes('src')).toBe('blob:asset-preview')
    expect(video.find('video').attributes('src')).toBe('blob:asset-preview')
  })
})
