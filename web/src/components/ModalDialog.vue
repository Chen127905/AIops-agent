<script setup lang="ts">
defineProps<{ title: string; eyebrow?: string; wide?: boolean }>()
defineEmits<{ close: [] }>()
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop" role="presentation" @mousedown.self="$emit('close')">
      <section class="modal-card" :class="{ wide }" role="dialog" aria-modal="true" :aria-label="title">
        <header class="modal-header">
          <div>
            <p v-if="eyebrow" class="section-label">{{ eyebrow }}</p>
            <h2>{{ title }}</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="$emit('close')">×</button>
        </header>
        <div class="modal-body"><slot /></div>
      </section>
    </div>
  </Teleport>
</template>
