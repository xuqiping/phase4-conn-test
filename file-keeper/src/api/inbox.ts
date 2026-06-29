import type { InboxMessage } from '@/types/inbox'
import { request, WorkReportApiError, isWorkReportApiError } from '@/api/workReport'

export { WorkReportApiError, isWorkReportApiError }

const BASE_PATH = '/api/client/work-report/inbox'

export async function listPendingInbox(
  baseUrl: string,
  token: string,
  deviceId: string,
  limit = 50,
): Promise<InboxMessage[]> {
  return request<InboxMessage[]>(baseUrl, token, deviceId, `?limit=${limit}`, {}, 1, BASE_PATH)
}

export async function confirmInboxMessage(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
  action: 'CONFIRM' | 'IGNORE',
  correctedPayload?: Record<string, unknown>,
): Promise<InboxMessage> {
  return request<InboxMessage>(
    baseUrl,
    token,
    deviceId,
    `/${id}/confirm`,
    {
      method: 'POST',
      body: JSON.stringify({ action, correctedPayload }),
    },
    1,
    BASE_PATH,
  )
}
