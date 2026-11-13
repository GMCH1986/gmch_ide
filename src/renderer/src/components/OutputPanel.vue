<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps<{
  lines: string[]
  version: number
  running: boolean
  selected: string
  tabs: Array<{ id: string; label: string; state: string }>
  collapsed: boolean
}>()

const emit = defineEmits<{
  clear: []
  select: [id: string]
  toggleCollapsed: []
}>()

const body = ref<HTMLPreElement>()
const panelId = 'output-panel'
const tablist = ref<HTMLElement | null>(null)
const selectedIndex = computed(() =>
  Math.max(
    0,
    props.tabs.findIndex((tab) => tab.id === props.selected)
  )
)
const activeTab = computed(() => props.tabs[selectedIndex.value])
const outputText = computed(() => props.lines.join('\n'))
/** 用户上翻时暂停自动滚动，回到底部后恢复 */
let follow = true

watch(
  () => props.version,
  async () => {
    if (props.lines.length === 0) follow = true
    if (!follow || props.collapsed) return
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

function tabId(index: number): string {
  return `output-tab-${index}`
}

function selectTab(index: number): void {
  const tab = props.tabs[index]
  if (!tab) return
  emit('select', tab.id)
  void nextTick(() => {
    const buttons = tablist.value?.querySelectorAll<HTMLElement>('[role="tab"]')
    buttons?.[index]?.focus()
  })
}

function focusTab(index: number): void {
  if (props.tabs.length === 0) return
  selectTab(Math.max(0, Math.min(index, props.tabs.length - 1)))
}

function onTabKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowRight') {
    event.preventDefault()
    focusTab((selectedIndex.value + 1) % props.tabs.length)
  } else if (event.key === 'ArrowLeft') {
    event.preventDefault()
    focusTab((selectedIndex.value - 1 + props.tabs.length) % props.tabs.length)
  } else if (event.key === 'Home') {
    event.preventDefault()
    focusTab(0)
  } else if (event.key === 'End') {
    event.preventDefault()
    focusTab(props.tabs.length - 1)
  }
}
</script>

<template>
  <div class="output-panel" :class="{ collapsed }">
    <div class="output-head">
      <div class="output-title-wrap">
        <span class="console-mark" aria-hidden="true" />
        <span class="output-title">运行日志</span>
      </div>
      <div
        ref="tablist"
        class="output-tabs"
        role="tablist"
        aria-label="日志来源"
        @keydown="onTabKeydown"
      >
        <button
          v-for="(tab, index) in tabs"
          :id="tabId(index)"
          :key="tab.id"
          type="button"
          class="output-tab"
          :class="{ active: selectedIndex === index }"
          role="tab"
          :aria-selected="selectedIndex === index"
          :aria-controls="collapsed ? undefined : panelId"
          :tabindex="selectedIndex === index ? 0 : -1"
          @click="emit('select', tab.id)"
        >
          <span class="state-dot" :class="tab.state" aria-hidden="true" />
          {{ tab.label }}
        </button>
      </div>
      <span v-if="running" class="running" role="status">
        <span class="running-dot" aria-hidden="true" />
        执行中
      </span>
      <span class="flex" />
      <button class="link-btn" type="button" :disabled="lines.length === 0" @click="emit('clear')">
        清空
      </button>
      <button
        class="link-btn collapse-btn"
        type="button"
        :aria-expanded="!collapsed"
        :aria-label="collapsed ? '展开运行日志' : '收起运行日志'"
        @click="emit('toggleCollapsed')"
      >
        <span class="collapse-chevron" :class="{ collapsed }" aria-hidden="true" />
        <span>{{ collapsed ? '展开' : '收起' }}</span>
      </button>
    </div>

    <div
      v-if="!collapsed"
      :id="panelId"
      class="output-content"
      role="tabpanel"
      :aria-labelledby="tabId(selectedIndex)"
      tabindex="0"
    >
      <pre
        v-if="lines.length > 0"
        ref="body"
        class="output-body"
        role="log"
        aria-live="polite"
        :aria-label="`${activeTab?.label ?? '运行'}日志`"
        @scroll="onScroll"
      >{{ outputText }}</pre>
      <div v-else class="output-empty">
        <span class="output-empty-mark" aria-hidden="true">&gt;_</span>
        <span>构建、运行和全栈服务日志会显示在这里</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.output-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--panel-soft);
}

.output-head {
  display: flex;
  flex: 0 0 38px;
  align-items: center;
  gap: 9px;
  min-height: 38px;
  padding: 0 11px;
  border-bottom: 1px solid var(--border);
  box-shadow: var(--inset-highlight);
  color: var(--text-secondary);
  font-size: 12px;
}

.output-panel.collapsed .output-head {
  border-bottom: 0;
}

.output-title-wrap {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 7px;
  padding-right: 5px;
}

.console-mark {
  position: relative;
  width: 13px;
  height: 10px;
  border: 1px solid var(--text-secondary);
  border-radius: 2px;
}

.console-mark::after {
  position: absolute;
  right: 2px;
  bottom: 1px;
  width: 4px;
  height: 1px;
  background: var(--accent);
  content: '';
}

.output-title {
  color: var(--text);
  font-size: 12px;
  font-weight: 650;
}

.output-tabs {
  display: flex;
  align-items: stretch;
  align-self: stretch;
  gap: 2px;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: thin;
}

.output-tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 100%;
  padding: 0 9px;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-family: inherit;
  font-size: 12px;
  white-space: nowrap;
  cursor: pointer;
}

.output-tab::after {
  position: absolute;
  right: 8px;
  bottom: 0;
  left: 8px;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: transparent;
  content: '';
}

.output-tab:hover {
  color: var(--text);
  background: var(--hover-surface);
}

.output-tab.active {
  color: var(--text-strong);
}

.output-tab.active::after {
  background: var(--accent);
}

.output-tab:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: -2px;
}

.state-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-tertiary);
}

.state-dot.building,
.state-dot.starting,
.state-dot.running {
  background: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.state-dot.ready,
.state-dot.success {
  background: var(--ok);
  box-shadow: 0 0 0 3px var(--ok-soft);
}

.state-dot.failed {
  background: var(--err);
  box-shadow: 0 0 0 3px var(--err-soft);
}

.state-dot.stopping {
  background: var(--warning);
}

.running {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  color: var(--accent-strong);
  font-size: 12px;
}

.running-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse 1.15s ease-in-out infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.35;
  }
}

.flex {
  flex: 1;
}

.link-btn {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 5px;
  min-height: 26px;
  padding: 3px 7px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: none;
  color: var(--text-secondary);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
}

.link-btn:hover:not(:disabled) {
  border-color: var(--border);
  background: var(--panel-raised);
  color: var(--text);
}

.link-btn:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: -1px;
}

.link-btn:disabled {
  opacity: 0.48;
  cursor: default;
}

.collapse-chevron {
  width: 7px;
  height: 7px;
  border-right: 1.5px solid currentColor;
  border-bottom: 1.5px solid currentColor;
  transform: translateY(-2px) rotate(45deg);
  transition: transform 120ms ease;
}

.collapse-chevron.collapsed {
  transform: translateY(-3px) rotate(225deg);
}

.output-content {
  display: flex;
  flex: 1;
  min-height: 0;
  outline: none;
}

.output-content:focus-visible {
  box-shadow: inset 0 0 0 2px var(--focus);
}

.output-body {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 10px 14px 12px;
  overflow: auto;
  color: var(--text);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.output-empty {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.output-empty-mark {
  padding: 3px 6px;
  border: 1px solid var(--border);
  border-radius: 4px;
  background: var(--panel-raised);
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
}
</style>
