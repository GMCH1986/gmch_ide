<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import type { MenuAction } from '../../shared/types'
import { useIdeStore } from './stores/ide'
import FileList from './components/FileList.vue'
import CodeEditor from './components/CodeEditor.vue'
import OutputPanel from './components/OutputPanel.vue'
import StatusBar from './components/StatusBar.vue'
import NewProjectDialog from './components/NewProjectDialog.vue'

const ide = useIdeStore()
const {
  backend,
  workspace,
  recentWorkspaces,
  files,
  activePath,
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
  showNewProject
} = storeToRefs(ide)

const backendLabel = computed(() => {
  switch (backend.value.status) {
    case 'ready':
      return '后端就绪'
    case 'starting':
      return '后端启动中…'
    case 'missing':
      return '后端未构建'
    case 'error':
      return '后端异常'
    default:
      return '后端已停止'
  }
})

function onCursor(line: number, col: number): void {
  ide.setCursor(line, col)
}

function revealWorkspace(path: string): void {
  void window.api.reveal(path)
}

onMounted(() => {
  void ide.init()
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
  offBackendStatus()
  offMenu()
  offClose()
  ide.disposeServiceEvents()
})
</script>

<template>
  <div class="ide">
    <header class="toolbar">
      <span class="brand">GMCH IDE</span>
      <button class="tb-btn" @click="ide.pickWorkspace()">打开文件夹</button>
      <button class="tb-btn" @click="ide.openNewProject()">新建工程</button>
      <button class="tb-btn" :disabled="!activePath || !dirty" @click="ide.save()">保存</button>
      <button
        class="tb-btn"
        :disabled="!workspace"
        title="刷新文件列表"
        @click="ide.refreshFiles()"
      >
        刷新
      </button>
      <span class="divider" />
      <button
        class="tb-btn primary"
        :disabled="!workspace || !services.configured || stackActive || busy"
        :title="services.message ?? services.path ?? '读取 .gmch/run.json'"
        @click="ide.startAllServices()"
      >
        启动全栈
      </button>
      <button class="tb-btn" :disabled="!stackActive" @click="ide.stopAllServices()">
        停止全部
      </button>
      <button class="tb-btn" :disabled="!previewUrl" @click="ide.openPreview()">打开预览</button>
      <span class="divider" />
      <button
        class="tb-btn"
        :disabled="!workspace || busy || stackActive"
        @click="ide.run('compileJava')"
      >
        编译
      </button>
      <button
        class="tb-btn"
        :disabled="!workspace || busy || stackActive"
        @click="ide.run('run')"
      >
        运行
      </button>
      <button class="tb-btn" :disabled="!busy" @click="ide.stop()">停止</button>
      <span class="flex" />
      <span class="notice">{{ notice }}</span>
      <span class="backend" :class="backend.status">{{ backendLabel }}</span>
    </header>

    <aside class="sidebar">
      <FileList
        :files="files"
        :active="activePath"
        :workspace="workspace"
        :recent-workspaces="recentWorkspaces"
        :workspace-key="workspace?.path ?? null"
        @open="ide.openFile"
        @switch-workspace="ide.switchWorkspace"
        @pick-workspace="ide.pickWorkspace"
        @reveal-workspace="revealWorkspace"
      />
    </aside>

    <main class="editor">
      <CodeEditor v-if="activePath" v-model="content" @cursor="onCursor" @save="ide.save()" />
      <div v-else class="empty">
        <h2>未打开文件</h2>
        <p v-if="backend.status !== 'ready'">{{ backend.message ?? '正在连接后端…' }}</p>
        <p v-else-if="!workspace">
          打开一个目录开始：菜单「文件 → 打开文件夹」或 <kbd>Ctrl+O</kbd>。
        </p>
        <p v-else>从左侧选择一个文件。</p>
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

    <section class="output">
      <OutputPanel
        :lines="outputLines"
        :version="outputVersion"
        :running="outputRunning"
        :selected="selectedOutput"
        :tabs="outputTabs"
        @select="ide.selectOutput"
        @clear="ide.clearOutput"
      />
    </section>

    <footer class="status">
      <StatusBar
        :workspace="workspace"
        :path="activePath"
        :cursor="cursor"
        :run-status="runStatus"
        :backend="backend"
        :services="services"
      />
    </footer>

    <NewProjectDialog v-if="showNewProject" />
  </div>
</template>
