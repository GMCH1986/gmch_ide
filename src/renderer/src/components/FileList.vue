<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { FileEntry, WorkspaceInfo } from '../../../shared/types'
import {
  buildFileTree,
  fileEntryToTreeItem,
  parentDirectories,
  visibleFileTree,
  type VisibleFileTreeItem
} from '../utils/file-tree'
import FileTreeNode from './FileTreeNode.vue'

const props = defineProps<{
  files: FileEntry[]
  active: string | null
  workspace: WorkspaceInfo | null
  workspaceKey?: string | null
  refreshing?: boolean
}>()

const emit = defineEmits<{
  open: [path: string]
  refreshFiles: []
}>()

const TREE_ROW_HEIGHT = 28
const VIRTUAL_OVERSCAN = 8

const tree = computed(() => buildFileTree(props.files))
const expanded = ref<Set<string>>(new Set())
const focusedPath = ref<string | null>(null)
const searchInput = ref('')
const searchQuery = ref('')
const searchOpen = ref(false)
const searchField = ref<HTMLInputElement | null>(null)
const searchTrigger = ref<HTMLButtonElement | null>(null)
const treeRoot = ref<HTMLElement | null>(null)
const treeViewport = ref<HTMLElement | null>(null)
const scrollTop = ref(0)
const viewportHeight = ref(480)
let searchTimer: ReturnType<typeof setTimeout> | null = null
let resizeObserver: ResizeObserver | null = null
let autoExpanded = false

const searchResults = computed<VisibleFileTreeItem[]>(() => {
  const query = searchQuery.value.trim().toLocaleLowerCase()
  if (!query) return []
  return props.files
    .filter((file) => file.path.toLocaleLowerCase().includes(query))
    .slice(0, 500)
    .map((file) => ({ item: fileEntryToTreeItem(file), depth: 0, parentPath: null }))
})

const visibleNodes = computed<VisibleFileTreeItem[]>(() => {
  if (searchQuery.value.trim()) return searchResults.value
  return visibleFileTree(tree.value, expanded.value)
})

const nodeIndex = computed(
  () => new Map(visibleNodes.value.map((node, index) => [node.item.path, index]))
)

const ariaPositions = computed(() => {
  const sizes = new Map<string, number>()
  const positions = new Map<string, number>()
  const seen = new Map<string, number>()
  for (const node of visibleNodes.value) {
    const key = node.parentPath ?? '__root__'
    sizes.set(key, (sizes.get(key) ?? 0) + 1)
  }
  for (const node of visibleNodes.value) {
    const key = node.parentPath ?? '__root__'
    const next = (seen.get(key) ?? 0) + 1
    seen.set(key, next)
    positions.set(node.item.path, next)
  }
  return { sizes, positions }
})

const virtualStart = computed(() =>
  Math.max(0, Math.floor(scrollTop.value / TREE_ROW_HEIGHT) - VIRTUAL_OVERSCAN)
)
const virtualEnd = computed(() =>
  Math.min(
    visibleNodes.value.length,
    Math.ceil((scrollTop.value + viewportHeight.value) / TREE_ROW_HEIGHT) + VIRTUAL_OVERSCAN
  )
)
const virtualNodes = computed(() => visibleNodes.value.slice(virtualStart.value, virtualEnd.value))
const treeHeight = computed(() => visibleNodes.value.length * TREE_ROW_HEIGHT)
const treeOffset = computed(() => virtualStart.value * TREE_ROW_HEIGHT)

/** 只有一个节点进入 Tab 序列，其余节点依靠方向键漫游。 */
const tabStopPath = computed(() => {
  const focused = focusedPath.value
  if (focused && virtualNodes.value.some((node) => node.item.path === focused)) return focused
  if (props.active && virtualNodes.value.some((node) => node.item.path === props.active)) {
    return props.active
  }
  return virtualNodes.value[0]?.item.path ?? null
})

const resultLabel = computed(() => {
  if (!searchQuery.value.trim()) return ''
  const suffix = searchResults.value.length === 500 ? '（最多显示 500 条）' : ''
  return `${searchResults.value.length} 个匹配${suffix}`
})

function expandPaths(paths: string[]): void {
  if (paths.length === 0) return
  const next = new Set(expanded.value)
  let changed = false
  for (const path of paths) {
    if (!next.has(path)) {
      next.add(path)
      changed = true
    }
  }
  if (changed) expanded.value = next
}

/** 默认展开源码和产物目录；当前文件变化时同步展开其父目录。 */
function initializeExpansion(): void {
  if (!autoExpanded && props.files.length > 0) {
    autoExpanded = true
    const next = new Set(expanded.value)
    for (const item of tree.value) {
      if (item.kind === 'directory' && (item.name === 'src' || item.name === 'build')) {
        next.add(item.path)
      }
    }
    expanded.value = next
  }
  if (props.active) expandPaths(parentDirectories(props.active))
}

function toggle(path: string): void {
  const next = new Set(expanded.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  expanded.value = next
}

function nodeElement(path: string): HTMLElement | null {
  const elements = treeRoot.value?.querySelectorAll<HTMLElement>('[data-tree-path]')
  if (!elements) return null
  return Array.from(elements).find((element) => element.dataset.treePath === path) ?? null
}

function measureViewport(): void {
  if (treeViewport.value) viewportHeight.value = treeViewport.value.clientHeight
}

function ensureIndexVisible(index: number): void {
  const viewport = treeViewport.value
  if (!viewport) return
  const rowTop = index * TREE_ROW_HEIGHT
  const rowBottom = rowTop + TREE_ROW_HEIGHT
  const visibleHeight = viewport.clientHeight || viewportHeight.value
  if (rowTop < viewport.scrollTop) {
    viewport.scrollTop = rowTop
  } else if (rowBottom > viewport.scrollTop + visibleHeight) {
    viewport.scrollTop = rowBottom - visibleHeight
  }
  scrollTop.value = viewport.scrollTop
}

function focusPath(path: string | null): void {
  if (!path) return
  focusedPath.value = path
  const index = nodeIndex.value.get(path)
  if (index !== undefined) ensureIndexVisible(index)
  void nextTick(() => {
    const element = nodeElement(path)
    element?.focus({ preventScroll: true })
  })
}

function focusIndex(index: number): void {
  const node = visibleNodes.value[index]
  if (node) focusPath(node.item.path)
}

function openNode(path: string): void {
  focusedPath.value = path
  emit('open', path)
}

function toggleNode(path: string): void {
  focusedPath.value = path
  toggle(path)
}

function onTreeFocus(event: FocusEvent): void {
  const target = (event.target as HTMLElement).closest<HTMLElement>('[data-tree-path]')
  if (target?.dataset.treePath) focusedPath.value = target.dataset.treePath
}

function onTreeKeydown(event: KeyboardEvent): void {
  const target = (event.target as HTMLElement).closest<HTMLElement>('[data-tree-path]')
  const path = target?.dataset.treePath
  if (!path) return
  const index = nodeIndex.value.get(path)
  if (index === undefined) return
  const node = visibleNodes.value[index]
  if (!node) return

  if (event.key === 'ArrowDown') {
    event.preventDefault()
    focusIndex(Math.min(index + 1, visibleNodes.value.length - 1))
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    focusIndex(Math.max(index - 1, 0))
  } else if (event.key === 'Home') {
    event.preventDefault()
    focusIndex(0)
  } else if (event.key === 'End') {
    event.preventDefault()
    focusIndex(visibleNodes.value.length - 1)
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    if (node.item.kind !== 'directory' || searchQuery.value.trim()) return
    if (!expanded.value.has(path)) {
      toggle(path)
    } else {
      const child = visibleNodes.value[index + 1]
      if (child && child.depth === node.depth + 1) void focusPath(child.item.path)
    }
  } else if (event.key === 'ArrowLeft') {
    event.preventDefault()
    if (searchQuery.value.trim()) return
    if (node.item.kind === 'directory' && expanded.value.has(path)) {
      toggle(path)
    } else if (node.parentPath) {
      focusPath(node.parentPath)
    }
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    if (node.item.kind === 'directory') toggleNode(path)
    else if (!node.item.binary) openNode(path)
  }
}

function onTreeScroll(event: Event): void {
  scrollTop.value = (event.currentTarget as HTMLElement).scrollTop
}

function refreshFiles(): void {
  if (!props.workspace) return
  emit('refreshFiles')
}

function clearSearch(): void {
  searchInput.value = ''
  searchQuery.value = ''
}

async function openSearch(): Promise<void> {
  searchOpen.value = true
  await nextTick()
  searchField.value?.focus()
}

function closeSearch(restoreFocus = true): void {
  searchOpen.value = false
  clearSearch()
  if (restoreFocus) void nextTick(() => searchTrigger.value?.focus())
}

function dismissSearch(): void {
  if (searchInput.value || searchQuery.value) {
    clearSearch()
    void nextTick(() => searchField.value?.focus())
    return
  }
  closeSearch()
}

function onSearchEscape(): void {
  if (searchInput.value || searchQuery.value) {
    clearSearch()
    return
  }
  closeSearch()
}

watch(searchInput, (value) => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    searchQuery.value = value.trim()
  }, 120)
})

watch(searchQuery, () => {
  scrollTop.value = 0
  if (treeViewport.value) treeViewport.value.scrollTop = 0
})

watch(
  treeViewport,
  (viewport) => {
    resizeObserver?.disconnect()
    if (!viewport) return
    measureViewport()
    resizeObserver = new ResizeObserver(measureViewport)
    resizeObserver.observe(viewport)
  },
  { flush: 'post' }
)

onMounted(() => {
  void nextTick(measureViewport)
})

onUnmounted(() => {
  resizeObserver?.disconnect()
  if (searchTimer) clearTimeout(searchTimer)
})

watch(() => props.files, () => {
  initializeExpansion()
  void nextTick(measureViewport)
}, { immediate: true })

watch(
  () => props.active,
  (path) => {
    if (!path) return
    expandPaths(parentDirectories(path))
    focusedPath.value = path
  }
)

watch(
  () => props.workspaceKey,
  () => {
    expanded.value = new Set()
    focusedPath.value = null
    searchInput.value = ''
    searchQuery.value = ''
    searchOpen.value = false
    scrollTop.value = 0
    if (treeViewport.value) treeViewport.value.scrollTop = 0
    autoExpanded = false
    initializeExpansion()
  }
)
</script>

<template>
  <div class="file-list">
    <div class="file-list-body">
      <div class="file-list-sticky">
        <div class="file-list-head" :class="{ 'is-searching': searchOpen }">
          <div v-if="!searchOpen" class="file-heading">
            <span>项目文件</span>
            <span class="count">{{ files.length }}</span>
            <span v-if="refreshing" class="sync-label" role="status">正在同步</span>
          </div>

          <div v-else class="file-search">
            <svg class="search-glyph" viewBox="0 0 20 20" aria-hidden="true">
              <circle cx="8.5" cy="8.5" r="4.8" />
              <path d="m12.1 12.1 4.1 4.1" />
            </svg>
            <input
              id="file-search-field"
              ref="searchField"
              v-model="searchInput"
              type="search"
              autocomplete="off"
              spellcheck="false"
              placeholder="筛选文件…"
              aria-label="按文件名或路径筛选项目文件"
              aria-describedby="file-filter-summary"
              @keydown.esc.prevent="onSearchEscape"
            />
            <span
              v-if="searchQuery"
              class="search-count"
              :title="resultLabel"
              aria-hidden="true"
            >
              {{ searchResults.length }}
            </span>
            <button
              type="button"
              class="search-dismiss"
              :title="searchInput ? '清除筛选' : '关闭筛选'"
              :aria-label="searchInput ? '清除筛选' : '关闭筛选'"
              @click="dismissSearch"
            >
              <svg viewBox="0 0 16 16" aria-hidden="true">
                <path d="m4.5 4.5 7 7m0-7-7 7" />
              </svg>
            </button>
            <span id="file-filter-summary" class="sr-only" aria-live="polite">
              {{ resultLabel }}
            </span>
          </div>

          <div class="head-actions">
            <button
              v-if="!searchOpen"
              ref="searchTrigger"
              type="button"
              class="search-button"
              title="筛选项目文件"
              aria-label="筛选项目文件"
              aria-controls="file-search-field"
              :aria-expanded="searchOpen"
              @click="openSearch"
            >
              <svg class="search-glyph" viewBox="0 0 20 20" aria-hidden="true">
                <circle cx="8.5" cy="8.5" r="4.8" />
                <path d="m12.1 12.1 4.1 4.1" />
              </svg>
            </button>
            <button
              type="button"
              class="refresh-button"
              :class="{ 'is-refreshing': refreshing }"
              :disabled="!workspace || refreshing"
              :title="refreshing ? '正在刷新本地目录…' : '刷新文件'"
              :aria-label="refreshing ? '正在刷新本地目录' : '刷新文件'"
              :aria-busy="refreshing"
              @click="refreshFiles"
            >
              <svg class="refresh-glyph" viewBox="0 0 20 20" aria-hidden="true">
                <path d="M15.6 6.2A6.5 6.5 0 1 0 16.4 12" />
                <path d="M15.8 2.9v3.8h-3.8" />
              </svg>
            </button>
          </div>
        </div>
      </div>

      <div
        v-if="visibleNodes.length > 0"
        ref="treeViewport"
        class="tree-viewport"
        role="tree"
        aria-label="项目文件树"
        @focusin="onTreeFocus"
        @keydown="onTreeKeydown"
        @scroll="onTreeScroll"
      >
        <div ref="treeRoot" class="tree" :style="{ height: `${treeHeight + 8}px` }">
          <div class="tree-window" :style="{ transform: `translateY(${treeOffset}px)` }">
            <FileTreeNode
              v-for="node in virtualNodes"
              :key="node.item.path"
              :item="node.item"
              :depth="node.depth"
              :active="active"
              :expanded="expanded"
              :focused-path="tabStopPath"
              :set-size="ariaPositions.sizes.get(node.parentPath ?? '__root__') ?? 1"
              :pos-in-set="ariaPositions.positions.get(node.item.path) ?? 1"
              @focus="focusedPath = $event"
              @toggle="toggleNode"
              @open="openNode"
            />
          </div>
        </div>
      </div>
      <div v-else-if="searchQuery" class="empty" role="status">
        没有匹配“{{ searchQuery }}”的文件。
        <button type="button" class="clear-search" @click="clearSearch">清除筛选</button>
      </div>
      <p v-else class="empty">暂无文件（确认项目包含 Gradle 结构）</p>
    </div>
  </div>
</template>
<style scoped>
.file-list {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: visible;
}

.file-list-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.file-list-sticky {
  z-index: 2;
  flex: 0 0 auto;
  background: var(--panel);
}

.file-list-head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 44px;
  padding: 7px 9px 7px 12px;
  color: var(--text-secondary);
  font-size: 12px;
  letter-spacing: 0.045em;
}

.file-heading {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 7px;
  min-width: 0;
  overflow: hidden;
}

.head-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 2px;
}

.count {
  display: inline-grid;
  min-width: 24px;
  height: 20px;
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel-soft);
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0;
  place-items: center;
}

.sync-label {
  overflow: hidden;
  color: var(--accent-strong);
  font-size: 11px;
  letter-spacing: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-button,
.refresh-button {
  display: inline-grid;
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  place-items: center;
}

.search-button:hover,
.refresh-button:hover:not(:disabled) {
  border-color: var(--border-strong);
  background: var(--panel-raised);
  color: var(--text);
}

.search-button:focus-visible,
.refresh-button:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: 0;
}

.refresh-button:disabled {
  opacity: 0.48;
  cursor: not-allowed;
}

.refresh-button.is-refreshing {
  opacity: 1;
  color: var(--accent);
  cursor: wait;
}

.refresh-glyph {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.7;
}

.refresh-button.is-refreshing .refresh-glyph {
  animation: refresh-spin 700ms linear infinite;
}

.search-glyph {
  flex: 0 0 14px;
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-width: 1.6;
}

.file-search {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 6px;
  min-width: 0;
  height: 30px;
  padding: 0 3px 0 8px;
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  background: var(--editor-bg);
}

.file-search:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-soft);
}

.file-search > .search-glyph {
  color: var(--text-tertiary);
}

.file-search input {
  flex: 1;
  min-width: 0;
  height: 28px;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font-family: inherit;
  font-size: 12px;
}

.file-search input::placeholder {
  color: var(--text-tertiary);
  opacity: 1;
}

.file-search input::-webkit-search-cancel-button {
  display: none;
}

.search-count {
  display: inline-grid;
  flex: 0 0 auto;
  min-width: 20px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--panel-raised);
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 11px;
  place-items: center;
}

.search-dismiss {
  display: inline-grid;
  flex: 0 0 22px;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  place-items: center;
}

.search-dismiss:hover {
  background: var(--panel-raised);
  color: var(--text);
}

.search-dismiss:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: 0;
}

.search-dismiss svg {
  width: 13px;
  height: 13px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-width: 1.5;
}

.tree-viewport {
  flex: 1;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.tree {
  position: relative;
  min-width: 0;
}

.tree-window {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  padding: 2px 6px 8px;
  will-change: transform;
}

.empty {
  margin: 8px;
  padding: 14px 12px;
  border: 1px dashed var(--border-strong);
  border-radius: 7px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.65;
}

.clear-search {
  display: block;
  margin-top: 8px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--accent-strong);
  font: inherit;
  cursor: pointer;
}

.clear-search:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}

@keyframes refresh-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
