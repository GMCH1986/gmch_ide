<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

const props = defineProps<{
  lines: string[]
  version: number
  running: boolean
}>()

const emit = defineEmits<{
  clear: []
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
      <span>输出</span>
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
  height: 28px;
  padding: 0 12px;
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--muted);
  border-bottom: 1px solid var(--border);
}
.running {
  color: var(--accent);
}
.flex {
  flex: 1;
}
.link-btn {
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
