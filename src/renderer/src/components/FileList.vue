<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { FileEntry, WorkspaceInfo } from '../../../shared/types'
import { buildFileTree, parentDirectories } from '../utils/file-tree'
import FileTreeNode from './FileTreeNode.vue'

const props = defineProps<{
  files: FileEntry[]
  active: string | null
  workspace: WorkspaceInfo | null
  recentWorkspaces: WorkspaceInfo[]
  workspaceKey?: string | null
}>()

const emit = defineEmits<{
  open: [path: string]
  switchWorkspace: [path: string]
  pickWorkspace: []
  revealWorkspace: [path: string]
}>()

const tree = computed(() => buildFileTree(props.files))
const recentProjects = computed(() =>
  props.recentWorkspaces.filter((item) => item.path !== props.workspace?.path).slice(0, 6)
)
const expanded = ref<Set<string>>(new Set())
const switcherOpen = ref(false)
const switcher = ref<HTMLElement | null>(null)
let autoExpanded = false

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

function selectWorkspace(path: string): void {
  switcherOpen.value = false
  emit('switchWorkspace', path)
}

function pickWorkspace(): void {
  switcherOpen.value = false
  emit('pickWorkspace')
}

function revealWorkspace(): void {
  if (!props.workspace) return
  switcherOpen.value = false
  emit('revealWorkspace', props.workspace.path)
}

function onDocumentPointerDown(event: PointerEvent): void {
  if (!switcherOpen.value) return
  if (switcher.value && !switcher.value.contains(event.target as Node)) {
    switcherOpen.value = false
  }
}

onMounted(() => document.addEventListener('pointerdown', onDocumentPointerDown))
onUnmounted(() => document.removeEventListener('pointerdown', onDocumentPointerDown))

watch(() => props.files, initializeExpansion, { immediate: true })
watch(
  () => props.active,
  (path) => {
    if (path) expandPaths(parentDirectories(path))
  }
)
watch(
  () => props.workspaceKey,
  () => {
    switcherOpen.value = false
    expanded.value = new Set()
    autoExpanded = false
    initializeExpansion()
  }
)
</script>

<template>
  <div class="file-list">
    <div ref="switcher" class="project-switcher">
      <div class="section-title">
        <span>项目根目录</span>
        <span class="current-chip">当前</span>
      </div>

      <button
        type="button"
        class="root-row"
        :class="{ open: switcherOpen }"
        :title="workspace?.path ?? '打开项目目录'"
        aria-haspopup="menu"
        :aria-expanded="switcherOpen"
        @click="switcherOpen = !switcherOpen"
      >
        <span class="folder-glyph" aria-hidden="true" />
        <span class="root-copy">
          <span class="root-name">{{ workspace?.name ?? '未打开项目' }}</span>
          <span class="root-path">{{ workspace?.path ?? '选择一个项目目录' }}</span>
        </span>
        <span class="switch-caret" :class="{ open: switcherOpen }">⌄</span>
      </button>

      <div v-if="switcherOpen" class="project-menu" role="menu" @keydown.esc="switcherOpen = false">
        <div class="menu-title">最近项目</div>
        <button
          v-for="project in recentProjects"
          :key="project.path"
          type="button"
          class="recent-row"
          role="menuitem"
          :title="project.path"
          @click="selectWorkspace(project.path)"
        >
          <span class="project-dot" />
          <span class="recent-copy">
            <span class="recent-name">{{ project.name }}</span>
            <span class="recent-path">{{ project.path }}</span>
          </span>
        </button>
        <p v-if="recentProjects.length === 0" class="menu-empty">暂无其他项目</p>

        <div class="menu-separator" />
        <button type="button" class="menu-action" role="menuitem" @click="pickWorkspace">
          <span class="action-icon">＋</span>
          打开其他文件夹…
        </button>
        <button
          v-if="workspace"
          type="button"
          class="menu-action"
          role="menuitem"
          @click="revealWorkspace"
        >
          <span class="action-icon">↗</span>
          在资源管理器中显示
        </button>
      </div>
    </div>

    <div class="file-list-body">
      <div class="file-list-head">
        <span>文件</span>
        <span class="count">{{ files.length }}</span>
      </div>
      <div v-if="tree.length > 0" class="tree" role="tree" aria-label="工程文件">
        <FileTreeNode
          v-for="item in tree"
          :key="item.path"
          :item="item"
          :depth="0"
          :active="active"
          :expanded="expanded"
          @toggle="toggle"
          @open="emit('open', $event)"
        />
      </div>
      <p v-else class="empty">暂无文件（确认工作区含 Gradle 工程）</p>
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
.project-switcher {
  position: relative;
  z-index: 3;
  flex: 0 0 auto;
  padding: 10px 8px 8px;
  background: var(--bg-soft);
  border-bottom: 1px solid var(--border);
}
.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px 6px;
  color: var(--muted);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.current-chip {
  padding: 0 5px;
  border: 1px solid rgba(78, 201, 176, 0.25);
  border-radius: 8px;
  color: var(--ok);
  font-size: 9px;
  letter-spacing: 0;
}
.root-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 7px 8px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: rgba(108, 155, 255, 0.07);
  color: var(--text);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}
.root-row:hover,
.root-row.open {
  border-color: rgba(108, 155, 255, 0.35);
  background: rgba(108, 155, 255, 0.12);
}
.root-row:focus-visible {
  outline: 1px solid rgba(108, 155, 255, 0.7);
  outline-offset: -1px;
}
.folder-glyph {
  position: relative;
  flex: 0 0 17px;
  width: 17px;
  height: 12px;
  margin-top: 3px;
  border-radius: 2px;
  background: #7fa6e8;
}
.folder-glyph::before {
  position: absolute;
  top: -3px;
  left: 1px;
  width: 7px;
  height: 4px;
  border-radius: 2px 2px 0 0;
  background: inherit;
  content: '';
}
.root-copy,
.recent-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  gap: 1px;
}
.root-name,
.recent-name {
  overflow: hidden;
  color: var(--text);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.root-path,
.recent-path {
  overflow: hidden;
  color: var(--muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.switch-caret {
  flex: 0 0 auto;
  color: var(--muted);
  font-size: 13px;
  transition: transform 120ms ease;
}
.switch-caret.open {
  transform: rotate(180deg);
}
.project-menu {
  position: absolute;
  top: calc(100% - 3px);
  right: 8px;
  left: 8px;
  max-height: min(390px, 62vh);
  overflow: auto;
  padding: 6px;
  border: 1px solid #414452;
  border-radius: 7px;
  background: #292a33;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.45);
}
.menu-title {
  padding: 5px 7px 7px;
  color: var(--muted);
  font-size: 10px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.recent-row,
.menu-action {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 7px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--text);
  font-family: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.recent-row:hover,
.menu-action:hover {
  background: rgba(108, 155, 255, 0.11);
}
.project-dot {
  flex: 0 0 6px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #6d7891;
}
.menu-empty {
  margin: 0;
  padding: 8px 7px 10px;
  color: var(--muted);
  font-size: 11px;
}
.menu-separator {
  height: 1px;
  margin: 5px 3px;
  background: var(--border);
}
.action-icon {
  flex: 0 0 16px;
  color: var(--accent);
  font-size: 14px;
  text-align: center;
}
.file-list-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding-bottom: 12px;
}
.file-list-head {
  display: flex;
  justify-content: space-between;
  padding: 9px 12px 6px;
  color: var(--muted);
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.count {
  color: var(--muted);
}
.tree {
  min-width: 0;
  padding: 2px 0 8px;
}
.empty {
  padding: 12px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>