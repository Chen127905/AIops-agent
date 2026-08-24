import { api } from './http'
import type { EntityId } from './types'

export interface Approval {
  id: EntityId
  taskId: EntityId
  toolName: string
  normalizedArguments: Record<string, unknown>
  risk: string
  status: string
  requestedBy: EntityId
  decidedBy: EntityId | null
  decisionComment: string | null
  expiresAt: string
  createdAt: string
}

export async function listPendingApprovals(): Promise<Approval[]> {
  return (await api.get<Approval[]>('/api/approvals')).data
}

export async function getApproval(id: EntityId): Promise<Approval> {
  return (await api.get<Approval>(`/api/approvals/${id}`)).data
}

export async function approve(id: EntityId, comment?: string): Promise<void> {
  await api.post(`/api/approvals/${id}/approve`, { comment })
}

export async function reject(id: EntityId, comment?: string): Promise<void> {
  await api.post(`/api/approvals/${id}/reject`, { comment })
}
