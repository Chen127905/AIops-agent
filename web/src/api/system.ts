import { api } from './http'

export interface HealthComponent {
  status: string
  details?: Record<string, unknown>
  components?: Record<string, HealthComponent>
}

export interface HealthResponse extends HealthComponent {
  groups?: string[]
}

export async function getSystemHealth(): Promise<HealthResponse> {
  return (await api.get<HealthResponse>('/actuator/health')).data
}

export async function getPrometheusMetrics(): Promise<string> {
  return (await api.get<string>('/actuator/prometheus', {
    headers: { Accept: 'text/plain' },
    transformResponse: [(value) => value],
  })).data
}
