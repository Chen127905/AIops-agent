import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

import {
  getAgentTask,
  streamAgentEvents,
  type AgentEvent,
  type AgentTask,
} from '../api/tickets'

const TERMINAL_EVENTS = new Set([
  'TASK_COMPLETED',
  'TASK_EXECUTION_FAILED',
  'TASK_REJECTED',
])

export function useAgentEvents(taskId: Ref<number | null>) {
  const events = ref<AgentEvent[]>([])
  const task = ref<AgentTask | null>(null)
  const connected = ref(false)
  const reconnecting = ref(false)
  const lastSequence = ref(0)
  let generation = 0
  let controller: AbortController | null = null

  const wait = (milliseconds: number) => new Promise<void>((resolve) => {
    window.setTimeout(resolve, milliseconds)
  })

  async function connect(id: number, currentGeneration: number): Promise<void> {
    while (generation === currentGeneration) {
      controller = new AbortController()
      try {
        connected.value = true
        reconnecting.value = false
        await streamAgentEvents(id, lastSequence.value, (event) => {
          if (event.sequence <= lastSequence.value) return
          events.value.push(event)
          lastSequence.value = event.sequence
          if (TERMINAL_EVENTS.has(event.eventType)) {
            generation += 1
            controller?.abort()
            void refreshTask(id)
          }
        }, controller.signal)
      } catch (error) {
        if (controller.signal.aborted || generation !== currentGeneration) return
      } finally {
        connected.value = false
      }
      if (generation !== currentGeneration) return
      reconnecting.value = true
      await wait(750)
    }
  }

  async function refreshTask(id = taskId.value): Promise<void> {
    if (id == null) return
    task.value = await getAgentTask(id)
  }

  function stop(): void {
    generation += 1
    controller?.abort()
    connected.value = false
    reconnecting.value = false
  }

  watch(taskId, (id) => {
    stop()
    events.value = []
    task.value = null
    lastSequence.value = 0
    if (id == null) return
    const currentGeneration = generation
    void refreshTask(id)
    void connect(id, currentGeneration)
  }, { immediate: true })

  onBeforeUnmount(stop)
  return { events, task, connected, reconnecting, lastSequence, refreshTask, stop }
}
