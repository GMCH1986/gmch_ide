<script setup lang="ts">
import type { FileEntry } from '../../../shared/types'

defineProps<{
  files: FileEntry[]
  active: string | null
}>()

const emit = defineEmits<{
  open: [path: string]
}>()
</script>

<template>
  <div class="file-list">
    <div class="file-list-head">
      <span>文件</span>
      <span class="count">{{ files.length }}</span>
    </div>
    <ul v-if="files.length > 0">
      <li
        v-for="file in files"
        :key="file.path"
        :class="{ active: file.path === active }"
        :title="file.path"
        @click="emit('open', file.path)"
      >
        {{ file.path }}
      </li>
    </ul>
    <p v-else class="empty">暂无文件（确认工作区含 Gradle 工程）</p>
  </div>
</template>

<style scoped>
.file-list {
  height: 100%;
  overflow: auto;
  padding-bottom: 12px;
}
.file-list-head {
  display: flex;
  justify-content: space-between;
  padding: 10px 12px 6px;
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--muted);
  text-transform: uppercase;
}
.count {
  color: var(--muted);
}
ul {
  list-style: none;
  margin: 0;
  padding: 0;
}
li {
  padding: 5px 12px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--muted);
  border-left: 2px solid transparent;
}
li:hover {
  background: rgba(108, 155, 255, 0.08);
  color: var(--text);
}
li.active {
  background: rgba(108, 155, 255, 0.14);
  border-left-color: var(--accent);
  color: var(--text);
}
.empty {
  padding: 12px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>
