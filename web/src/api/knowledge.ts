import { api } from './http'
import type { EntityId } from './types'

export interface EvidenceChunk {
  documentId: EntityId
  documentVersion: number
  chunkIndex: number
  source: string
  content: string
  score: number
  citationId: string
}

export interface KnowledgeDocumentCommand {
  name: string
  source: string
  mediaType: string
  content: string
  metadata?: Record<string, string>
}

export interface KnowledgeBootstrapResult {
  total: number
  published: number
  skipped: number
}

export async function searchKnowledge(query: string): Promise<EvidenceChunk[]> {
  return (await api.get<EvidenceChunk[]>('/api/knowledge/search', {
    params: { query, topK: 8 },
  })).data
}

export async function initializeBuiltInKnowledge(): Promise<KnowledgeBootstrapResult> {
  return (await api.post<KnowledgeBootstrapResult>(
    '/api/knowledge/bootstrap',
    undefined,
    { timeout: 120_000 },
  )).data
}

export async function ingestDocument(document: KnowledgeDocumentCommand): Promise<EntityId> {
  const response = await api.post<{ documentId: EntityId }>('/api/knowledge/documents', document)
  return response.data.documentId
}

export async function publishDocumentVersion(
  documentId: EntityId,
  document: KnowledgeDocumentCommand,
): Promise<EntityId> {
  const response = await api.post<{ documentId: EntityId }>(
    `/api/knowledge/documents/${documentId}/versions`,
    document,
  )
  return response.data.documentId
}
