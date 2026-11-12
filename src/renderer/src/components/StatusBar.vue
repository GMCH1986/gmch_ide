<script setup lang="ts">
import type { BackendInfo, RunStatus } from '../../../shared/types'

defineProps<{
  workspace: { path: string; name: string } | null
  path: string | null
  cursor: { line: number; col: number }
  runStatus: RunStatus
  backend: BackendInfo
}>()

const runLabels: Record<RunStatus, string> = {
  idle: '空闲',
  running: '运行中',
  success: '构建成功',
  failed: '构建失败'
}
</script>

<template>
  <div class="status-bar">
    <span class="workspace">{{ workspace?.name ?? '未打开工作区' }}</span>
    <span class="sep">|</span>
    <span class="path" :title="path ?? ''">{{ path ?? '—' }}</span>
    <span class="flex" />
    <span>Ln {{ cursor.line }}, Col {{ cursor.col }}</span>
    <span class="sep">|</span>
    <span :class="['run', runStatus]">{{ runLabels[runStatus] }}</span>
    <span class="sep">|</span>
    <span :class="['backend', backend.status]">
      {{ backend.status === 'ready' ? '就绪' : backend.status === 'starting' ? '启动中' : '未就绪' }}
    </span>
  </div>
</template>

<style scoped>
.status-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 100%;
  padding: 0 12px;
  font-size: 11px;
  color: var(--muted);
  background: var(--bg-soft);
  border-top: 1px solid var(--border);
}
.flex {
  flex: 1;
}
.path {
  max-width: 46%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sep {
  color: #44444f;
}
.run.running {
  color: var(--accent);
}
.run.success {
  color: var(--ok);
}
.run.failed {
  color: var(--err);
}
.backend.ready {
  color: var(--ok);
}
.backend.error,
.backend.missing {
  color: var(--err);
}
</style>
