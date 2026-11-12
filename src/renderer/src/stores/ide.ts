import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { BackendInfo, FileEntry, RunStatus, RunTask } from '../../../shared/types'
import * as api from '../api/client'

export const useIdeStore = defineStore('ide', () => {
  const backend = ref<BackendInfo>({ status: 'starting', url: null, token: null })
  const workspace = ref<{ path: string; name: string } | null>(null)
  const files = ref<FileEntry[]>([])
  const activePath = ref<string | null>(null)
  const content = ref('')
  const savedContent = ref('')
  const cursor = ref({ line: 1, col: 1 })
  const log = ref<string[]>([])
  const runStatus = ref<RunStatus>('idle')
  const busy = ref(false)
  const notice = ref('')

  const dirty = computed(() => content.value !== savedContent.value)
  const activeFile = computed(
    () => files.value.find((file) => file.path === activePath.value) ?? null
  )

  let disposeRun: (() => void) | null = null

  /** 主进程可能还在启动 sidecar：轮询到终态（就绪/失败/缺失）为止 */
  async function waitForBackend(timeoutMs = 40_000): Promise<BackendInfo> {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const info = await window.api.getBackend()
      if (info.status === 'ready' || info.status === 'missing' || info.status === 'error') {
        return info
      }
      await new Promise((resolve) => setTimeout(resolve, 300))
    }
    return window.api.getBackend()
  }

  async function init(): Promise<void> {
    const info = await waitForBackend()
    backend.value = info
    if (info.status !== 'ready' || !info.url || !info.token) {
      notice.value = info.message ?? '后端未就绪'
      return
    }

    api.connect(info.url, info.token)
    workspace.value = await window.api.getWorkspace()
    if (workspace.value) {
      await refreshFiles()
      const first = files.value.find((file) => file.path.endsWith('.java')) ?? files.value[0]
      if (first) await openFile(first.path)
    }
  }

  function confirmDiscard(): boolean {
    return !dirty.value || window.confirm('当前文件有未保存的修改，确定放弃并继续？')
  }

  async function refreshFiles(): Promise<void> {
    files.value = await api.listFiles()
  }

  async function openFile(path: string): Promise<void> {
    if (path === activePath.value) return
    if (!confirmDiscard()) return
    const text = await api.readFile(path)
    activePath.value = path
    content.value = text
    savedContent.value = text
  }

  async function save(): Promise<boolean> {
    if (!activePath.value) return false
    try {
      await api.writeFile(activePath.value, content.value)
      savedContent.value = content.value
      notice.value = '已保存'
      return true
    } catch (error) {
      notice.value = error instanceof Error ? error.message : '保存失败'
      return false
    }
  }

  async function pickWorkspace(): Promise<void> {
    if (!confirmDiscard()) return
    const picked = await window.api.pickWorkspace()
    if (!picked) return

    workspace.value = picked
    activePath.value = null
    content.value = ''
    savedContent.value = ''
    await refreshFiles()

    const first = files.value.find((file) => file.path.endsWith('.java')) ?? files.value[0]
    if (first) await openFile(first.path)
  }

  /** 输出面板按行累积，最多保留 2000 行，避免长任务把内存和 DOM 拖垮 */
  function appendLog(line: string): void {
    log.value.push(line)
    if (log.value.length > 2000) log.value.splice(0, log.value.length - 2000)
  }

  function clearLog(): void {
    log.value = []
  }

  async function run(task: RunTask): Promise<void> {
    if (busy.value) return
    if (!workspace.value) {
      notice.value = '请先打开工作区'
      return
    }

    busy.value = true
    runStatus.value = 'running'
    log.value = []
    appendLog(`> gradlew ${task} --console=plain -q`)

    disposeRun = api.runTask(task, {
      onLog: appendLog,
      onExit: (code) => {
        busy.value = false
        runStatus.value = code === '0' ? 'success' : 'failed'
        appendLog(`[进程退出，代码 ${code}]`)
      },
      onError: () => {
        busy.value = false
        runStatus.value = 'failed'
        appendLog('[输出连接中断]')
      }
    })
  }

  async function stop(): Promise<void> {
    disposeRun?.()
    disposeRun = null
    busy.value = false
    runStatus.value = 'idle'
    try {
      await api.stopRun()
      appendLog('[已请求停止]')
    } catch {
      appendLog('[停止请求失败]')
    }
  }

  function setCursor(line: number, col: number): void {
    cursor.value = { line, col }
  }

  return {
    backend,
    workspace,
    files,
    activePath,
    content,
    savedContent,
    cursor,
    log,
    runStatus,
    busy,
    notice,
    dirty,
    activeFile,
    init,
    refreshFiles,
    openFile,
    save,
    pickWorkspace,
    run,
    stop,
    clearLog,
    setCursor
  }
})
