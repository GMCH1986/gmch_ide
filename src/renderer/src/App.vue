<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import type { MenuAction } from '../../shared/types'
import { useIdeStore } from './stores/ide'
import { fileBadge } from './utils/file-tree'
import FileList from './components/FileList.vue'
import ProjectSwitcher from './components/ProjectSwitcher.vue'
import OutputPanel from './components/OutputPanel.vue'
import BuildToolsPanel from './components/BuildToolsPanel.vue'
import StatusBar from './components/StatusBar.vue'
import NewProjectDialog from './components/NewProjectDialog.vue'
import QuickOpen from './components/QuickOpen.vue'
import ShortcutHelp from './components/ShortcutHelp.vue'

const CodeEditor = defineAsyncComponent({
  loader: () => import('./components/CodeEditor.vue'),
  delay: 120,
  timeout: 10000
})

const ide = useIdeStore()
const {
  backend,
  workspace,
  recentWorkspaces,
  recentFiles,
  files,
  activePath,
  activeFile,
  content,
  cursor,
  runStatus,
  busy,
  services,
  selectedOutput,
  stackActive,
  outputRunning,
  outputTabs,
  outputLines,
  outputVersion,
  previewUrl,
  dirty,
  notice,
  refreshingFiles,
  startupClass,
  startupClasses,
  startupScanning,
  startupError,
  buildTools,
  buildToolsLoading,
  buildToolsError,
  activeBuildCommandId,
  showNewProject
} = storeToRefs(ide)

const quickOpen = ref(false)
const showShortcutHelp = ref(false)
const outputCollapsed = ref(true)
const outputHeight = ref(190)
const buildToolsCollapsed = ref(false)
const OUTPUT_MIN_HEIGHT = 104
const OUTPUT_MAX_HEIGHT = 430

const activeDirectory = computed(() => {
  if (!activePath.value) return ''
  const parts = activePath.value.split('/')
  return parts.length > 1 ? parts.slice(0, -1).join(' / ') : '项目根目录'
})

const activeBadge = computed(() => fileBadge(activeFile.value?.name ?? ''))

const stackDisabledReason = computed(() => {
  if (!workspace.value) return '先打开项目'
  if (!services.value.configured) return '未配置 .gmch/run.json'
  if (stackActive.value) return '服务运行中'
  if (busy.value) return '当前任务运行中'
  return ''
})

const shellStyle = computed(() => ({
  '--output-size': `${outputCollapsed.value ? 38 : outputHeight.value}px`
}))

watch(
  () => outputLines.value.length,
  (length, previous) => {
    if (length > 0 && (previous ?? 0) === 0) outputCollapsed.value = false
  },
  { immediate: true }
)

function onCursor(line: number, col: number): void {
  ide.setCursor(line, col)
}

function revealWorkspace(path: string): void {
  void window.api.reveal(path)
}

function showQuickOpen(): void {
  if (quickOpen.value || showNewProject.value || showShortcutHelp.value) return
  if (!workspace.value) {
    notice.value = '请先打开项目'
    return
  }
  quickOpen.value = true
}

async function openQuickFile(path: string): Promise<void> {
  quickOpen.value = false
  await ide.openFile(path)
}

function clampOutputHeight(value: number): number {
  return Math.max(OUTPUT_MIN_HEIGHT, Math.min(OUTPUT_MAX_HEIGHT, value))
}

function startOutputResize(event: PointerEvent): void {
  if (outputCollapsed.value) return
  event.preventDefault()
  const startY = event.clientY
  const startHeight = outputHeight.value
  document.body.classList.add('is-resizing')

  const onPointerMove = (moveEvent: PointerEvent): void => {
    outputHeight.value = clampOutputHeight(startHeight + startY - moveEvent.clientY)
  }
  const onPointerUp = (): void => {
    document.body.classList.remove('is-resizing')
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
  }

  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
}

function onOutputResizeKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    outputHeight.value = clampOutputHeight(outputHeight.value + 16)
  } else if (event.key === 'ArrowDown') {
    event.preventDefault()
    outputHeight.value = clampOutputHeight(outputHeight.value - 16)
  } else if (event.key === 'Home') {
    event.preventDefault()
    outputHeight.value = OUTPUT_MAX_HEIGHT
  } else if (event.key === 'End') {
    event.preventDefault()
    outputHeight.value = OUTPUT_MIN_HEIGHT
  }
}

function toggleOutput(): void {
  outputCollapsed.value = !outputCollapsed.value
  if (!outputCollapsed.value) outputHeight.value = clampOutputHeight(outputHeight.value)
}

function onGlobalKeydown(event: KeyboardEvent): void {
  if (event.key === 'F1') {
    if (showNewProject.value || quickOpen.value) return
    event.preventDefault()
    showShortcutHelp.value = !showShortcutHelp.value
    return
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'p') {
    event.preventDefault()
    showQuickOpen()
  }
}

onMounted(() => {
  void ide.init()
  window.addEventListener('keydown', onGlobalKeydown)
})

const offBackendStatus = window.api.onBackendStatus((info) => {
  ide.updateBackendStatus(info)
})

// 菜单快捷键统一由主进程分发，渲染层只做动作映射
const offMenu = window.api.onMenuAction((action: MenuAction) => {
  if (action === 'open-workspace') void ide.pickWorkspace()
  else if (action === 'new-project') ide.openNewProject()
  else if (action === 'save') void ide.save()
  else if (action === 'compile') void ide.run('compileJava')
  else if (action === 'run') void ide.run('run')
  else if (action === 'stop') void ide.stop()
})

// 关闭守卫：确认（并按需保存）之后再放行主进程关闭窗口
let closePending = false
const offClose = window.api.onCloseRequest(async () => {
  if (closePending) return
  closePending = true
  try {
    if (dirty.value) {
      const shouldSave = window.confirm('有未保存的修改，是否保存后退出？')
      if (shouldSave) {
        if (!(await ide.save())) {
          await window.api.cancelClose()
          return
        }
      } else if (!window.confirm('不保存将丢失当前修改，确定直接退出？')) {
        await window.api.cancelClose()
        return
      }
    }
    await window.api.closeWindow()
  } finally {
    closePending = false
  }
})

onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  offBackendStatus()
  offMenu()
  offClose()
  ide.disposeServiceEvents()
})
</script>

<template>
  <div
    class="ide"
    :class="{ 'build-tools-collapsed': buildToolsCollapsed }"
    :style="shellStyle"
  >
    <header class="toolbar">
      <ProjectSwitcher
        :workspace="workspace"
        :recent-workspaces="recentWorkspaces"
        @switch-workspace="ide.switchWorkspace"
        @pick-workspace="ide.pickWorkspace"
        @reveal-workspace="revealWorkspace"
        @new-project="ide.openNewProject"
      />

      <div class="toolbar-group" aria-label="文件操作">
        <button
          class="tb-btn"
          :disabled="!activePath || !dirty"
          title="保存当前文件"
          @click="ide.save()"
        >
          保存
        </button>
        <button class="tb-btn quick-trigger" :disabled="!workspace" @click="showQuickOpen">
          快速打开
          <kbd>Ctrl P</kbd>
        </button>
      </div>

      <span class="divider" />

      <div class="toolbar-group" aria-label="全栈操作">
        <button
          class="tb-btn primary"
          :disabled="!workspace || !services.configured || stackActive || busy"
          :title="services.message ?? services.path ?? '读取 .gmch/run.json'"
          :aria-describedby="stackDisabledReason ? 'stack-disabled-reason' : undefined"
          @click="ide.startAllServices()"
        >
          启动全栈
        </button>
        <button class="tb-btn" :disabled="!stackActive" @click="ide.stopAllServices()">
          停止全部
        </button>
        <button class="tb-btn" :disabled="!previewUrl" @click="ide.openPreview()">打开预览</button>
        <span v-if="stackDisabledReason" id="stack-disabled-reason" class="action-hint" role="note">
          {{ stackDisabledReason }}
        </span>
      </div>

      <span class="divider" />

      <div class="toolbar-group" aria-label="构建操作">
        <button
          class="tb-btn"
          :disabled="!workspace || busy || stackActive"
          @click="ide.run('compileJava')"
        >
          编译
        </button>
        <button
          class="tb-btn run-action"
          :disabled="!workspace || busy || stackActive"
          @click="ide.run('run')"
        >
          运行
        </button>
        <button class="tb-btn" :disabled="!busy" @click="ide.stop()">停止</button>
      </div>

      <div class="toolbar-group" aria-label="帮助">
        <button
          class="tb-btn shortcut-trigger"
          title="快捷键 (F1)"
          aria-haspopup="dialog"
          :aria-expanded="showShortcutHelp"
          @click="showShortcutHelp = true"
        >
          <span class="shortcut-symbol" aria-hidden="true">?</span>
          <span class="shortcut-label">快捷键</span>
          <kbd>F1</kbd>
        </button>
      </div>

      <span class="flex" />
      <span v-if="notice" class="notice" role="status" aria-live="polite">{{ notice }}</span>
    </header>

    <aside class="sidebar">
      <FileList
        :files="files"
        :active="activePath"
        :workspace="workspace"
        :workspace-key="workspace?.path ?? null"
        :refreshing="refreshingFiles"
        @open="ide.openFile"
        @refresh-files="ide.refreshFiles(true)"
      />
    </aside>

    <main class="editor">
      <div v-if="activePath" class="editor-frame">
        <div class="editor-header">
          <div class="editor-file">
            <span class="file-mark" aria-hidden="true">{{ activeBadge }}</span>
            <span class="editor-file-copy">
              <span class="editor-file-name" :title="activePath">
                {{ activeFile?.name ?? '当前文件' }}
              </span>
              <span class="editor-file-path" :title="activePath">{{ activeDirectory }}</span>
            </span>
            <span v-if="dirty" class="dirty-indicator" title="有未保存的修改" />
          </div>
          <span class="editor-path" :title="activePath">{{ activePath }}</span>
        </div>
        <div class="editor-surface">
          <CodeEditor v-model="content" @cursor="onCursor" @save="ide.save()" />
        </div>
      </div>
      <div v-else class="empty">
        <span class="empty-mark" aria-hidden="true">J</span>
        <h2>从源文件开始</h2>
        <p v-if="backend.status !== 'ready'">{{ backend.message ?? '正在连接后端…' }}</p>
        <p v-else-if="!workspace">
          打开一个目录开始：菜单「文件 → 打开文件夹」或 <kbd>Ctrl+O</kbd>。
        </p>
        <p v-else>在左侧项目树中选择文件，或按 <kbd>Ctrl+P</kbd> 快速打开。</p>
        <button
          v-if="backend.status !== 'ready'"
          class="tb-btn"
          :disabled="backend.status === 'starting'"
          @click="ide.retryBackend()"
        >
          重试连接
        </button>
      </div>
    </main>

    <section class="output" :class="{ collapsed: outputCollapsed, resizable: !outputCollapsed }">
      <div
        v-if="!outputCollapsed"
        class="output-resizer"
        role="separator"
        aria-orientation="horizontal"
        aria-label="调整运行日志高度"
        :aria-valuemin="OUTPUT_MIN_HEIGHT"
        :aria-valuemax="OUTPUT_MAX_HEIGHT"
        :aria-valuenow="outputHeight"
        tabindex="0"
        @pointerdown="startOutputResize"
        @keydown="onOutputResizeKeydown"
      />
      <OutputPanel
        :lines="outputLines"
        :version="outputVersion"
        :running="outputRunning"
        :selected="selectedOutput"
        :tabs="outputTabs"
        :collapsed="outputCollapsed"
        @select="ide.selectOutput"
        @clear="ide.clearOutput"
        @toggle-collapsed="toggleOutput"
      />
    </section>

    <aside class="build-sidebar">
      <BuildToolsPanel
        :projects="buildTools"
        :loading="buildToolsLoading"
        :error="buildToolsError"
        :active-command-id="activeBuildCommandId"
        :running="busy"
        :collapsed="buildToolsCollapsed"
        :workspace-open="Boolean(workspace)"
        :workspace-name="workspace?.name"
        @run="ide.runBuildCommand"
        @refresh="ide.loadBuildTools(true)"
        @toggle-collapsed="buildToolsCollapsed = !buildToolsCollapsed"
      />
    </aside>

    <footer class="status">
      <StatusBar
        :workspace="workspace"
        :path="activePath"
        :cursor="cursor"
        :run-status="runStatus"
        :backend="backend"
        :services="services"
        :startup-class="startupClass"
        :startup-classes="startupClasses"
        :startup-scanning="startupScanning"
        :startup-error="startupError"
        @open-startup-class="ide.openStartupClass"
        @rescan-startup="ide.loadStartupClasses()"
      />
    </footer>

    <NewProjectDialog v-if="showNewProject" />
    <QuickOpen
      v-if="quickOpen"
      :files="files"
      :recent-files="recentFiles"
      :workspace-name="workspace?.name"
      @close="quickOpen = false"
      @open="openQuickFile"
    />
    <ShortcutHelp v-if="showShortcutHelp" @close="showShortcutHelp = false" />
  </div>
</template>
