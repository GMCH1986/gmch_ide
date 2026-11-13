<script setup lang="ts">
import { computed } from 'vue'
import { fileBadge, isBuildArtifact, type FileTreeItem } from '../utils/file-tree'

const props = defineProps<{
  item: FileTreeItem
  depth: number
  active: string | null
  expanded: ReadonlySet<string>
  focusedPath: string | null
  setSize: number
  posInSet: number
}>()

const emit = defineEmits<{
  focus: [path: string]
  toggle: [path: string]
  open: [path: string]
}>()

const isDirectory = computed(() => props.item.kind === 'directory')
const isExpanded = computed(() => props.expanded.has(props.item.path))
const isSelected = computed(() => !isDirectory.value && props.item.path === props.active)
const artifact = computed(() => isBuildArtifact(props.item.path))
const buildRoot = computed(
  () => isDirectory.value && artifact.value && !props.item.path.includes('/')
)
const badge = computed(() => fileBadge(props.item.name))
const badgeClass = computed(() => {
  const lower = props.item.name.toLowerCase()
  if (lower.endsWith('.java')) return 'java'
  if (lower.endsWith('.gradle') || lower.endsWith('.kts')) return 'gradle'
  if (lower.endsWith('.jar')) return 'jar'
  if (lower.endsWith('.class')) return 'class'
  return 'generic'
})
const title = computed(() => {
  if (isDirectory.value) return props.item.path
  return props.item.binary ? `${props.item.path}（二进制文件，不支持预览）` : props.item.path
})

function activate(): void {
  if (isDirectory.value) emit('toggle', props.item.path)
  else if (!props.item.binary) emit('open', props.item.path)
}
</script>

<template>
  <div
    class="tree-row"
    :class="{
      active: isSelected,
      artifact,
      binary: item.binary,
      directory: isDirectory
    }"
    role="treeitem"
    :aria-level="depth + 1"
    :aria-selected="isDirectory ? undefined : isSelected"
    :aria-expanded="isDirectory ? isExpanded : undefined"
    :aria-setsize="setSize"
    :aria-posinset="posInSet"
    :data-tree-path="item.path"
    :tabindex="focusedPath === item.path ? 0 : -1"
    :style="{ paddingLeft: `${8 + depth * 15}px` }"
    :title="title"
    @focus="emit('focus', item.path)"
    @click="emit('focus', item.path); activate()"
  >
    <span v-if="isDirectory" class="chevron" :class="{ expanded: isExpanded }" aria-hidden="true" />
    <span v-if="isDirectory" class="dir-mark" :class="{ open: isExpanded }" aria-hidden="true" />
    <span v-else class="file-badge" :class="badgeClass" aria-hidden="true">{{ badge }}</span>
    <span class="label">{{ item.name }}</span>
    <span v-if="buildRoot" class="artifact-tag">构建产物</span>
    <span v-if="item.binary" class="binary-tag">二进制</span>
  </div>
</template>

<style scoped>
.tree-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  height: 28px;
  padding-right: 8px;
  overflow: hidden;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: var(--text-secondary);
  font-family: inherit;
  font-size: 12px;
  line-height: 1.3;
  text-align: left;
  cursor: pointer;
  scroll-margin-block: 38px;
}

.tree-row:hover {
  background: var(--hover-surface);
  color: var(--text-strong);
}

.tree-row:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: -2px;
  background: var(--hover-surface);
}

.tree-row.active {
  border-color: var(--accent-border);
  background: var(--selected-surface);
  color: var(--text-strong);
}

.tree-row.active::before {
  position: absolute;
  inset: 5px auto 5px 0;
  width: 2px;
  border-radius: 0 2px 2px 0;
  background: var(--accent);
  content: '';
}

.tree-row.directory {
  color: var(--text);
  font-weight: 560;
}

.tree-row.artifact {
  color: var(--text-tertiary);
}

.tree-row.binary:not(:focus-visible) {
  opacity: 0.72;
}

.chevron {
  flex: 0 0 8px;
  width: 8px;
  height: 8px;
  margin-right: 1px;
  border-right: 1.5px solid currentColor;
  border-bottom: 1.5px solid currentColor;
  transform: rotate(-45deg);
  transition: transform 120ms ease;
}

.chevron.expanded {
  transform: rotate(45deg) translate(-1px, -1px);
}

.dir-mark {
  position: relative;
  flex: 0 0 15px;
  width: 15px;
  height: 11px;
  margin-right: 1px;
  border-radius: 2px;
  background: var(--folder-mark);
}

.dir-mark::before {
  position: absolute;
  top: -2px;
  left: 1px;
  width: 6px;
  height: 3px;
  border-radius: 2px 2px 0 0;
  background: inherit;
  content: '';
}

.dir-mark.open {
  background: var(--folder-mark-open);
}

.file-badge {
  display: grid;
  flex: 0 0 18px;
  width: 18px;
  height: 18px;
  margin-right: 1px;
  border: 1px solid var(--file-badge-border);
  border-radius: 4px;
  background: var(--file-badge-bg);
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  place-items: center;
}

.file-badge.java {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.file-badge.gradle {
  border-color: var(--ok-border);
  background: var(--ok-soft);
  color: var(--ok);
}

.file-badge.jar {
  border-color: var(--info-border);
  background: var(--info-soft);
  color: var(--info);
}

.file-badge.class {
  border-color: var(--teal-border);
  background: var(--teal-soft);
  color: var(--teal);
}

.label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-tag,
.binary-tag {
  flex: 0 0 auto;
  margin-left: auto;
  padding: 1px 5px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel-soft);
  color: var(--text-tertiary);
  font-size: 11px;
  line-height: 1.4;
}

.binary-tag {
  margin-left: 0;
}
</style>
