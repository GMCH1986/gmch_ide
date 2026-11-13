<script setup lang="ts">
import { computed } from 'vue'
import type {
  BackendInfo,
  RunStatus,
  ServicesStatus,
  StartupClassInfo
} from '../../../shared/types'
import StartupClassPicker from './StartupClassPicker.vue'

const props = defineProps<{
  workspace: { path: string; name: string } | null
  path: string | null
  cursor: { line: number; col: number }
  runStatus: RunStatus
  backend: BackendInfo
  services: ServicesStatus
  startupClass: StartupClassInfo | null
  startupClasses: StartupClassInfo[]
  startupScanning: boolean
  startupError: string
}>()

const emit = defineEmits<{
  'open-startup-class': [path: string]
  'rescan-startup': []
}>()

const runLabels: Record<RunStatus, string> = {
  idle: '任务空闲',
  running: '构建运行中',
  success: '构建成功',
  failed: '构建失败'
}

const backendLabel = computed(() => {
  if (props.backend.status === 'ready') return '后端就绪'
  if (props.backend.status === 'starting') return '后端启动中'
  if (props.backend.status === 'missing') return '后端未构建'
  if (props.backend.status === 'error') return '后端异常'
  return '后端已停止'
})

const stackState = computed(() => {
  const failed = props.services.services.some((service) => service.state === 'failed')
  if (failed) return { label: '全栈失败', className: 'failed' }
  if (props.services.stopping) return { label: '全栈停止中', className: 'running' }
  if (props.services.running) {
    const ready = props.services.services.filter((service) => service.state === 'ready').length
    if (ready === props.services.services.length) {
      return { label: '全栈就绪', className: 'ready' }
    }
    return { label: '全栈启动中', className: 'running' }
  }
  return { label: '全栈空闲', className: 'idle' }
})
</script>

<template>
  <div class="status-bar" aria-label="IDE 状态">
    <span class="workspace" :title="workspace?.path ?? ''">
      <span class="workspace-mark" aria-hidden="true" />
      {{ workspace?.name ?? '未打开项目' }}
    </span>
    <span class="path" :title="path ?? ''">{{ path ?? '未选择文件' }}</span>
    <span class="flex" />
    <span class="metric cursor-metric" :aria-label="`第 ${cursor.line} 行，第 ${cursor.col} 列`">
      <span class="metric-label">行</span>
      {{ cursor.line }}
      <span class="metric-label">列</span>
      {{ cursor.col }}
    </span>
    <StartupClassPicker
      :workspace-open="Boolean(workspace)"
      :startup-class="startupClass"
      :startup-classes="startupClasses"
      :startup-scanning="startupScanning"
      :startup-error="startupError"
      @open-startup-class="emit('open-startup-class', $event)"
      @rescan-startup="emit('rescan-startup')"
    />
    <span :class="['metric', 'state', 'run', runStatus]">
      <span class="state-dot" aria-hidden="true" />
      {{ runLabels[runStatus] }}
    </span>
    <span :class="['metric', 'state', 'stack', stackState.className]">
      <span class="state-dot" aria-hidden="true" />
      {{ stackState.label }}
    </span>
    <span :class="['metric', 'state', 'backend', backend.status]">
      <span class="state-dot" aria-hidden="true" />
      {{ backendLabel }}
    </span>
  </div>
</template>

<style scoped>
.status-bar {
  position: relative;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 12px;
  height: 100%;
  padding: 0 12px;
  border-top: 1px solid var(--border);
  background: var(--bg-deep);
  color: var(--text-secondary);
  font-size: 12px;
}

.workspace {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  max-width: 190px;
  overflow: hidden;
  color: var(--text);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-mark {
  flex: 0 0 7px;
  width: 7px;
  height: 7px;
  border: 1px solid var(--accent);
  border-radius: 2px;
}

.flex {
  flex: 1;
}

.path {
  min-width: 0;
  max-width: 44%;
  overflow: hidden;
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}

.cursor-metric {
  gap: 4px;
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
}

.metric-label {
  color: var(--text-tertiary);
  font-family: var(--font-ui);
  font-size: 11px;
}

.state {
  gap: 6px;
}

.state-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--text-tertiary);
}

.run.running,
.stack.running {
  color: var(--accent-strong);
}

.run.running .state-dot,
.stack.running .state-dot {
  background: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-soft);
}

.run.success,
.stack.ready,
.backend.ready {
  color: var(--ok);
}

.run.success .state-dot,
.stack.ready .state-dot,
.backend.ready .state-dot {
  background: var(--ok);
}

.run.failed,
.stack.failed,
.backend.error,
.backend.missing {
  color: var(--err);
}

.run.failed .state-dot,
.stack.failed .state-dot,
.backend.error .state-dot,
.backend.missing .state-dot {
  background: var(--err);
}

.backend.starting {
  color: var(--accent-strong);
}

.backend.starting .state-dot {
  background: var(--accent);
}
@media (max-width: 1180px) {
  .path {
    display: none;
  }
}

@media (max-width: 980px) {
  .status-bar {
    gap: 8px;
    padding: 0 9px;
  }

  .cursor-metric {
    display: none;
  }
}
</style>
