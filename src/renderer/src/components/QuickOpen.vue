<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import type { FileEntry } from '../../../shared/types'

const props = defineProps<{
  files: FileEntry[]
  recentFiles: string[]
  workspaceName?: string
}>()

const emit = defineEmits<{
  close: []
  open: [path: string]
}>()

const query = ref('')
const selectedIndex = ref(0)
const input = ref<HTMLInputElement | null>(null)
const dialog = ref<HTMLElement | null>(null)
let previousFocus: HTMLElement | null = null

const recentSet = computed(() => new Set(props.recentFiles))
const filesByPath = computed(() => new Map(props.files.map((file) => [file.path, file])))

const results = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  if (!needle) {
    return props.recentFiles
      .map((path) => filesByPath.value.get(path))
      .filter((file): file is FileEntry => Boolean(file))
      .slice(0, 80)
  }

  return props.files
    .map((file) => {
      const lowerName = file.name.toLocaleLowerCase()
      const lowerPath = file.path.toLocaleLowerCase()
      let score = -1
      if (lowerName === needle) score = 5
      else if (lowerName.startsWith(needle)) score = 4
      else if (lowerName.includes(needle)) score = 3
      else if (lowerPath.includes(needle)) score = 1
      return { file, score }
    })
    .filter((entry) => entry.score >= 0)
    .sort((a, b) => {
      if (a.score !== b.score) return b.score - a.score
      return a.file.name.localeCompare(b.file.name, undefined, {
        numeric: true,
        sensitivity: 'base'
      })
    })
    .slice(0, 80)
    .map((entry) => entry.file)
})

const activeDescendant = computed(() =>
  results.value.length > 0 ? `quick-open-option-${selectedIndex.value}` : undefined
)

function select(index: number): void {
  if (results.value.length === 0) return
  selectedIndex.value = Math.max(0, Math.min(index, results.value.length - 1))
}

function openSelected(): void {
  const file = results.value[selectedIndex.value]
  if (file) emit('open', file.path)
}

function onQueryInput(): void {
  selectedIndex.value = 0
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
    return
  }
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    select(selectedIndex.value + 1)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    select(selectedIndex.value - 1)
    return
  }
  if (event.key === 'Home') {
    event.preventDefault()
    select(0)
    return
  }
  if (event.key === 'End') {
    event.preventDefault()
    select(results.value.length - 1)
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    openSelected()
    return
  }
  if (event.key === 'Tab' && dialog.value) {
    const focusable = Array.from(
      dialog.value.querySelectorAll<HTMLElement>(
        'input, button:not(:disabled), [tabindex]:not([tabindex="-1"])'
      )
    )
    if (focusable.length === 0) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }
}

onMounted(() => {
  previousFocus = document.activeElement as HTMLElement | null
  void nextTick(() => input.value?.focus())
})

onUnmounted(() => {
  previousFocus?.focus?.()
})
</script>

<template>
  <div class="quick-overlay" @pointerdown.self="emit('close')">
    <section
      ref="dialog"
      class="quick-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="quick-open-title"
      @keydown="onKeydown"
    >
      <h2 id="quick-open-title" class="sr-only">快速打开项目文件</h2>
      <div class="quick-search">
        <svg viewBox="0 0 20 20" aria-hidden="true">
          <circle cx="8.5" cy="8.5" r="4.8" />
          <path d="m12.1 12.1 4.1 4.1" />
        </svg>
        <input
          ref="input"
          v-model="query"
          type="text"
          role="combobox"
          aria-autocomplete="list"
          aria-haspopup="listbox"
          aria-controls="quick-open-results"
          :aria-activedescendant="activeDescendant"
          :aria-expanded="true"
          autocomplete="off"
          spellcheck="false"
          :placeholder="`在 ${workspaceName ?? '当前项目'} 中快速打开文件`"
          @input="onQueryInput"
        />
        <kbd>Esc</kbd>
      </div>

      <div class="quick-meta" aria-live="polite">
        <span>{{ query ? `${results.length} 个匹配` : '最近文件' }}</span>
        <span>↑↓ 选择 · Enter 打开</span>
      </div>

      <div id="quick-open-results" class="quick-results" role="listbox" aria-label="文件搜索结果">
        <button
          v-for="(file, index) in results"
          :id="`quick-open-option-${index}`"
          :key="file.path"
          type="button"
          class="quick-result"
          :class="{ active: index === selectedIndex }"
          role="option"
          :aria-selected="index === selectedIndex"
          @mouseenter="select(index)"
          @click="emit('open', file.path)"
        >
          <span class="file-mark" aria-hidden="true">J</span>
          <span class="result-copy">
            <span class="result-name">
              {{ file.name }}
              <span v-if="recentSet.has(file.path)" class="recent-tag">最近</span>
            </span>
            <span class="result-path">{{ file.path }}</span>
          </span>
        </button>
        <div v-if="results.length === 0" class="quick-empty" role="status">
          {{ query ? '没有找到匹配的文件' : '暂无最近打开的文件，输入文件名开始搜索' }}
        </div>
      </div>

      <footer class="quick-footer">
        <span><kbd>Ctrl</kbd><kbd>P</kbd> 快速打开</span>
        <span>{{ files.length }} 个项目文件</span>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.quick-overlay {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: max(72px, 10vh) 24px 24px;
  background: var(--overlay);
  backdrop-filter: blur(4px);
}

.quick-dialog {
  display: flex;
  flex-direction: column;
  width: min(680px, calc(100vw - 48px));
  max-height: min(560px, calc(100vh - 110px));
  overflow: hidden;
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  background: var(--panel-raised);
  box-shadow: var(--shadow-dialog);
}

.quick-search {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 50px;
  padding: 0 14px;
  border-bottom: 1px solid var(--border);
  background: var(--editor-bg);
}

.quick-search svg {
  flex: 0 0 18px;
  width: 18px;
  height: 18px;
  fill: none;
  stroke: var(--accent);
  stroke-linecap: round;
  stroke-width: 1.7;
}

.quick-search input {
  min-width: 0;
  flex: 1;
  height: 44px;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text-strong);
  font-family: inherit;
  font-size: 14px;
}

.quick-search input::placeholder {
  color: var(--text-tertiary);
  opacity: 1;
}

.quick-meta,
.quick-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--text-tertiary);
  font-size: 12px;
}

.quick-meta {
  padding: 9px 14px 8px;
}

.quick-results {
  min-height: 0;
  overflow: auto;
  padding: 0 7px 8px;
}

.quick-result {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 46px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--text);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

.quick-result:hover,
.quick-result.active {
  border-color: var(--border);
  background: var(--hover-surface);
}

.quick-result.active .file-mark {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.file-mark {
  display: grid;
  flex: 0 0 22px;
  width: 22px;
  height: 22px;
  border: 1px solid var(--file-badge-border);
  border-radius: 4px;
  background: var(--file-badge-bg);
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 700;
  place-items: center;
}

.result-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  gap: 3px;
}

.result-name {
  display: flex;
  align-items: center;
  gap: 7px;
  overflow: hidden;
  color: var(--text-strong);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-tag {
  padding: 1px 5px;
  border: 1px solid var(--accent-border);
  border-radius: 999px;
  color: var(--accent-strong);
  font-size: 11px;
  font-weight: 500;
}

.result-path {
  overflow: hidden;
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quick-empty {
  padding: 28px 16px;
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
}

.quick-footer {
  padding: 9px 14px;
  border-top: 1px solid var(--border);
  background: var(--panel-soft);
}

.quick-footer kbd + kbd {
  margin-left: 3px;
}
</style>
