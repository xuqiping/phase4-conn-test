import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ReportViewer from '@/components/work-report/ReportViewer.vue'

vi.mock('@/composables/useI18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

describe('ReportViewer', () => {
  it('renders completion rate card', () => {
    const wrapper = mount(ReportViewer, {
      global: { plugins: [createPinia()] },
      props: {
        report: {
          id: 1,
          title: '日报',
          content: '内容',
          reportType: 'DAILY',
          generatedAt: '2026-06-29T10:00:00Z',
          status: 'GENERATED',
          completionRate: 0.75,
          consecutiveMissDays: 0,
        },
      },
    })

    expect(wrapper.text()).toContain('workReport.completionRate')
    expect(wrapper.text()).toContain('75%')
  })

  it('shows consecutive miss days when greater than zero', () => {
    const wrapper = mount(ReportViewer, {
      global: { plugins: [createPinia()] },
      props: {
        report: {
          id: 1,
          title: '周报',
          content: '内容',
          reportType: 'WEEKLY',
          generatedAt: '2026-06-29T10:00:00Z',
          status: 'GENERATED',
          completionRate: 0.5,
          consecutiveMissDays: 2,
        },
      },
    })

    expect(wrapper.text()).toContain('workReport.consecutiveMissDays')
    expect(wrapper.text()).toContain('2')
  })
})
