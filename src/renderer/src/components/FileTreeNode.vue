<script setup lang="ts">
import { computed } from 'vue'
import { fileBadge, isBuildArtifact, type FileTreeItem } from '../utils/file-tree'

const props = defineProps<{
  item: FileTreeItem
  depth: number
  active: string | null
  expanded: Set<string>
}>()

const emit = defineEmits<{
  toggle: [path: string]
  open: [path: string]
}>()

const isDirectory = computed(() => props.item.kind === 'directory')
const isExpanded = computed(() => props.expanded.has(props.item.path))
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
  return props.item.binary
    ? `${props.item.path}（二进制文件，不支持预览）`
    : props.item.path
})

function activate(): void {
  if (isDirectory.value) {
    emit('toggle', props.item.path)
  } else if (!props.item.binary) {
    emit('open', props.item.path)
  }
}
</script>

<template>
  <div class="tree-node">
    <button
      type="button"
      class="tree-row"
      :class="{
        active: item.kind === 'file' && item.path === active,
        artifact,
        binary: item.binary,
        directory: isDirectory
      }"
      :style="{ paddingLeft: `${8 + depth * 14}px` }"
      :title="title"
      :aria-expanded="isDirectory ? isExpanded : undefined"
      @click="activate"
    >
      <span v-if="isDirectory" class="chevron">{{ isExpanded ? '▾' : '▸' }}</span>
      <span v-else class="file-badge" :class="badgeClass">{{ badge }}</span>
      <span class="label">{{ item.name }}</span>
      <span v-if="buildRoot" class="artifact-tag">产物</span>
    </button>

    <div v-if="isDirectory && isExpanded" class="children">
      <FileTreeNode
        v-for="child in item.children"
        :key="child.path"
        :item="child"
        :depth="depth + 1"
        :active="active"
        :expanded="expanded"
        @toggle="emit('toggle', $event)"
        @open="emit('open', $event)"
      />
    </div>
  </div>
</template>

<style scoped>
.tree-node {
  min-width: 0;
}
.tree-row {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  height: 24px;
  padding-right: 8px;
  border: none;
  border-left: 2px solid transparent;
  background: transparent;
  color: var(--muted);
  font-family: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.tree-row:hover {
  background: rgba(108, 155, 255, 0.08);
  color: var(--text);
}
.tree-row:focus-visible {
  outline: 1px solid rgba(108, 155, 255, 0.7);
  outline-offset: -1px;
}
.tree-row.active {
  border-left-color: var(--accent);
  background: rgba(108, 155, 255, 0.14);
  color: var(--text);
}
.tree-row.artifact {
  color: #9ba5b8;
}
.tree-row.binary {
  cursor: default;
  opacity: 0.72;
}
.tree-row.directory {
  color: #c7c9d1;
  font-weight: 500;
}
.chevron {
  flex: 0 0 12px;
  width: 12px;
  font-size: 10px;
  color: var(--muted);
}
.file-badge {
  flex: 0 0 14px;
  width: 14px;
  height: 14px;
  border-radius: 3px;
  font-size: 9px;
  line-height: 14px;
  text-align: center;
  color: #aeb7c8;
  background: rgba(255, 255, 255, 0.06);
}
.file-badge.java {
  color: #f0b36a;
  background: rgba(240, 179, 106, 0.13);
}
.file-badge.gradle {
  color: #75c5a5;
  background: rgba(117, 197, 165, 0.13);
}
.file-badge.jar {
  color: #88b7ff;
  background: rgba(136, 183, 255, 0.13);
}
.file-badge.class {
  color: #71d2d2;
  background: rgba(113, 210, 210, 0.12);
}
.label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.artifact-tag {
  flex: 0 0 auto;
  margin-left: auto;
  padding: 0 4px;
  border: 1px solid rgba(136, 183, 255, 0.3);
  border-radius: 3px;
  color: #8fa9d4;
  font-size: 9px;
  line-height: 14px;
}
.children {
  min-width: 0;
}
</style>
