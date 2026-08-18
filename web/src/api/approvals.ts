import { api } from './http'

export interface Approval {
  id: number
  taskId: number
  toolName: string
  normalizedArguments: Record<string, unknown>
  risk: string
  status: string
  requestedBy: number
  decidedBy: number | null
  decisionComment: string | null
  expiresAt: string
  createdAt: string
}

export async function listPendingApprovals(): Promise<Approval[]> {
  return (await api.get<Approval[]>('/api/approvals')).data
}

export async function approve(id: number, comment?: string): Promise<void> {
  await api.post(`/api/approvals/${id}/approve`, { comment })
}

export async function reject(id: number, comment?: string): Promise<void> {
  await api.post(`/api/approvals/${id}/reject`, { comment })
}
