import { api } from './http'

export interface EvaluationCase {
  id: string
  group: string
  title: string
  scenarioKey: string
}

export interface EvaluationMetrics {
  totalCases: number
  passedCases: number
  passRate: number
  toolPrecision: number
  toolRecall: number
  citationAccuracy: number
  approvalInterceptionRate: number
  leakageCount: number
  p95LatencyMs: number
}

export interface EvaluationRun {
  runId: string
  mode: string
  provider: string
  model: string
  promptVersion: string
  knowledgeVersion: string
  status: string
  metrics: EvaluationMetrics
  startedAt: string
  finishedAt: string | null
}

export interface EvaluationRunRequest {
  mode: 'MOCK' | 'LIVE'
  provider?: 'QWEN' | 'DEEPSEEK'
  model?: string
  promptVersion?: string
  knowledgeVersion?: string
  caseIds?: string[]
}

export async function listEvaluationCases(): Promise<EvaluationCase[]> {
  return (await api.get<EvaluationCase[]>('/api/evaluations/cases')).data
}

export async function runMockEvaluation(): Promise<EvaluationRun> {
  return (await api.post<EvaluationRun>('/api/evaluations/runs', { mode: 'MOCK' })).data
}

export async function runEvaluation(request: EvaluationRunRequest): Promise<EvaluationRun> {
  return (await api.post<EvaluationRun>('/api/evaluations/runs', request)).data
}

export async function getEvaluationRun(runId: string): Promise<EvaluationRun> {
  return (await api.get<EvaluationRun>(`/api/evaluations/runs/${runId}`)).data
}
