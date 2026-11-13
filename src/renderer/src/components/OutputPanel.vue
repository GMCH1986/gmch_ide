<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

const props = defineProps<{
  lines: string[]
  version: number
  running: boolean
  selected: string
  tabs: Array<{ id: string; label: string; state: string }>
}>()

const emit = defineEmits<{
  clear: []
  select: [id: string]
}>()

const body = ref<HTMLPreElement>()
/** 用户上翻时暂停自动滚动，回到底部后恢复 */
let follow = true

watch(
  () => props.version,
  async () => {
    if (props.lines.length === 0) follow = true
    if (!follow) return
    await nextTick()
    const element = body.value
    if (element) element.scrollTop = element.scrollHeight
  }
)

function onScroll(): void {
  const element = body.value
  if (!element) return
  follow = element.scrollHeight - element.scrollTop - element.clientHeight < 24
}
</script>

<template>
  <div class="output-panel">
    <div class="output-head">
      <span class="output-title">输出</span>
      <div class="output-tabs" role="tablist" aria-label="输出服务">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          type="button"
          class="output-tab"
          :class="{ active: selected === tab.id }"
          role="tab"
          :aria-selected="selected === tab.id"
          @click="emit('select', tab.id)"
        >
          <span class="state-dot" :class="tab.state" />
          {{ tab.label }}
        </button>
      </div>
      <span v-if="running" class="running">运行中…</span>
      <span class="flex" />
      <button class="link-btn" @click="emit('clear')">清空</button>
    </div>
    <pre ref="body" class="output-body" @scroll="onScroll">{{ lines.join('\n') }}</pre>
  </div>
</template>

<style scoped>
.output-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--bg-soft);
}
.output-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 30px;
  padding: 0 10px;
  font-size: 11px;
  letter-spacing: 0.04em;
  color: var(--muted);
  border-bottom: 1px solid var(--border);
}
.output-title {
  flex: 0 0 auto;
  letter-spacing: 0.08em;
}
.output-tabs {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
  overflow-x: auto;
}
.output-tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 22px;
  padding: 0 7px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: var(--muted);
  font-family: inherit;
  font-size: 11px;
  white-space: nowrap;
  cursor: pointer;
}
.output-tab:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.05);
}
.output-tab.active {
  border-color: rgba(108, 155, 255, 0.28);
  background: rgba(108, 155, 255, 0.1);
  color: var(--text);
}
.state-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #666a76;
}
.state-dot.building,
.state-dot.starting,
.state-dot.running {
  background: var(--accent);
  box-shadow: 0 0 5px rgba(108, 155, 255, 0.5);
}
.state-dot.ready,
.state-dot.success {
  background: var(--ok);
}
.state-dot.failed {
  background: var(--err);
}
.state-dot.stopping {
  background: #d7a85f;
}
.running {
  flex: 0 0 auto;
  color: var(--accent);
}
.flex {
  flex: 1;
}
.link-btn {
  flex: 0 0 auto;
  border: none;
  background: none;
  color: var(--muted);
  font-size: 11px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
}
.link-btn:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.06);
}
.output-body {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 8px 12px;
  overflow: auto;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  color: #c8c8d0;
}
</style>
