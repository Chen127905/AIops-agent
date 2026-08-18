import { api } from './http'

export interface EvidenceChunk {
  documentId: number
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

export async function searchKnowledge(query: string): Promise<EvidenceChunk[]> {
  return (await api.get<EvidenceChunk[]>('/api/knowledge/search', {
    params: { query, topK: 8 },
  })).data
}

export async function ingestDocument(document: KnowledgeDocumentCommand): Promise<number> {
  const response = await api.post<{ documentId: number }>('/api/knowledge/documents', document)
  return response.data.documentId
}

export async function publishDocumentVersion(
  documentId: number,
  document: KnowledgeDocumentCommand,
): Promise<number> {
  const response = await api.post<{ documentId: number }>(
    `/api/knowledge/documents/${documentId}/versions`,
    document,
  )
  return response.data.documentId
}
