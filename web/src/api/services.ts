import { api } from './http'
import type { EntityId } from './types'

export interface ManagedService {
  id: EntityId
  tenantId: EntityId
  name: string
  systemName: string
  environment: string
  baseUrl: string
  healthPath: string
  metricsPath: string | null
  logsPath: string | null
  dependenciesPath: string | null
  operationsPath: string | null
  bearerTokenEnv: string | null
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export type ManagedServiceInput = Omit<ManagedService, 'id' | 'tenantId' | 'createdAt' | 'updatedAt'>

export interface ServiceHealth {
  service: string
  status: string
  summary: string
  scenarioState: string
}

export async function listManagedServices(): Promise<ManagedService[]> {
  return (await api.get<ManagedService[]>('/api/managed-services')).data
}
export async function createManagedService(input: ManagedServiceInput): Promise<ManagedService> {
  return (await api.post<ManagedService>('/api/managed-services', input)).data
}
export async function updateManagedService(id: EntityId, input: ManagedServiceInput): Promise<ManagedService> {
  return (await api.put<ManagedService>(`/api/managed-services/${id}`, input)).data
}
export async function deleteManagedService(id: EntityId): Promise<void> {
  await api.delete(`/api/managed-services/${id}`)
}
export async function testManagedService(id: EntityId): Promise<ServiceHealth> {
  return (await api.post<ServiceHealth>(`/api/managed-services/${id}/test`)).data
}
