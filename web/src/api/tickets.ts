import { api, AUTH_UNAUTHORIZED_EVENT, TOKEN_STORAGE_KEY } from './http'
import type { EntityId } from './types'

export type TicketStatus = 'OPEN' | 'TRIAGING' | 'DIAGNOSING' | 'WAITING_APPROVAL' | 'EXECUTING' | 'VERIFYING' | 'RESOLVED' | 'FAILED' | 'CANCELLED' | 'TIMEOUT' | 'MANUAL_REQUIRED'
export type TicketSeverity = 'UNKNOWN' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'

export interface Ticket {
  id: EntityId
  tenantId: EntityId
  reporterId: EntityId
  title: string
  description: string
  affectedService: string | null
  category: string | null
  scenarioKey: string | null
  severity: TicketSeverity
  status: TicketStatus
  resolutionSummary: string | null
  createdAt: string
  updatedAt: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface CreateTicket {
  title: string
  description: string
  affectedService?: string
  category?: string
  scenarioKey?: string
  severity: TicketSeverity
}

export interface OpsScenario {
  key: string
  service: string
  category: string
  severity: TicketSeverity
  requiresApproval: boolean
}

export async function listScenarios(): Promise<OpsScenario[]> {
  return (await api.get<OpsScenario[]>('/api/scenarios')).data
}

export interface AgentTask {
  id: EntityId
  ticketId: EntityId
  status: string
  maxSteps: number
  timeoutSeconds: number
  maxTokens: number
  stepsUsed: number
  tokensUsed: number
  errorSummary: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface AgentEvent {
  id: EntityId
  tenantId: EntityId
  taskId: EntityId
  sequence: number
  eventType: string
  payload: Record<string, unknown>
  createdAt: string
}

export interface AgentTaskResult {
  taskId: EntityId
  ticketId: EntityId
  status: string
  category: string | null
  urgency: string | null
  rootCause: string | null
  proposedAction: string | null
  diagnosisSummary: string | null
  actionArguments: Record<string, unknown>
  confidence: number
  report: string | null
  citations: string[]
  plannedTools: string[]
  evidence: Record<string, unknown>[]
  observations: Record<string, unknown>[]
  remediationSteps: string[]
  verificationSteps: string[]
  rollbackPlan: string | null
  errorSummary: string | null
}

export type ConversationRole = 'USER' | 'ASSISTANT'
export type ConversationMessageStatus = 'SENT' | 'FAILED'

export interface ConversationMessage {
  id: EntityId
  role: ConversationRole
  status: ConversationMessageStatus
  content: string
  provider: string | null
  modelName: string | null
  inputTokens: number
  outputTokens: number
  latencyMs: number
  createdAt: string
}

export interface TicketConversation {
  id: EntityId
  ticketId: EntityId
  summary: string | null
  summarizedThroughMessageId: EntityId | null
  createdAt: string
  updatedAt: string
  messages: ConversationMessage[]
}

export async function listTickets(params: {
  status?: TicketStatus
  page?: number
  size?: number
} = {}): Promise<PageResult<Ticket>> {
  return (await api.get<PageResult<Ticket>>('/api/tickets', { params })).data
}

export async function getTicket(id: EntityId): Promise<Ticket> {
  return (await api.get<Ticket>(`/api/tickets/${id}`)).data
}

export async function createTicket(command: CreateTicket): Promise<Ticket> {
  return (await api.post<Ticket>('/api/tickets', command)).data
}

export async function cancelTicket(id: EntityId): Promise<void> {
  await api.post(`/api/tickets/${id}/cancel`)
}

export async function startAgentTask(ticketId: EntityId): Promise<AgentTask> {
  return (await api.post<AgentTask>(`/api/tickets/${ticketId}/agent-tasks`)).data
}

export async function getAgentTask(taskId: EntityId): Promise<AgentTask> {
  return (await api.get<AgentTask>(`/api/agent-tasks/${taskId}`)).data
}

export async function getLatestAgentTask(ticketId: EntityId): Promise<AgentTask | null> {
  const response = await api.get<AgentTask>(`/api/tickets/${ticketId}/agent-tasks/latest`)
  return response.status === 204 ? null : response.data
}

export async function getAgentTaskResult(taskId: EntityId): Promise<AgentTaskResult> {
  return (await api.get<AgentTaskResult>(`/api/agent-tasks/${taskId}/result`)).data
}

export async function cancelAgentTask(taskId: EntityId): Promise<AgentTask> {
  return (await api.post<AgentTask>(`/api/agent-tasks/${taskId}/cancel`)).data
}

export async function getTicketConversation(
  ticketId: EntityId,
): Promise<TicketConversation | null> {
  const response = await api.get<TicketConversation>(
    `/api/tickets/${ticketId}/conversation`,
  )
  return response.status === 204 ? null : response.data
}

export async function sendTicketConversationMessage(
  ticketId: EntityId,
  content: string,
): Promise<TicketConversation> {
  return (await api.post<TicketConversation>(
    `/api/tickets/${ticketId}/conversation/messages`,
    { content },
  )).data
}

export async function streamAgentEvents(
  taskId: EntityId,
  after: number,
  onEvent: (event: AgentEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const base = import.meta.env.VITE_API_BASE_URL ?? ''
  const token = localStorage.getItem(TOKEN_STORAGE_KEY)
  const response = await fetch(`${base}/api/agent-tasks/${taskId}/events?after=${after}`, {
    headers: {
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    signal,
  })
  if (response.status === 401) {
    localStorage.removeItem(TOKEN_STORAGE_KEY)
    window.dispatchEvent(new Event(AUTH_UNAUTHORIZED_EVENT))
  }
  if (!response.ok || !response.body) {
    throw new Error(`SSE request failed with status ${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (!signal.aborted) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const frame = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const data = frame.split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart())
        .join('\n')
      if (data) onEvent(JSON.parse(data) as AgentEvent)
      boundary = buffer.indexOf('\n\n')
    }
    if (done) return
  }
}
