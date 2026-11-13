import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type {
  BackendInfo,
  FileEntry,
  RunStatus,
  RunTask,
  ServiceLogEvent,
  ServiceStateEvent,
  ServicesStatus,
  WorkspaceInfo
} from '../../../shared/types'
import * as api from '../api/client'

function messageOf(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}

export const useIdeStore = defineStore('ide', () => {
  const backend = ref<BackendInfo>({ status: 'starting', url: null, token: null })
  const workspace = ref<WorkspaceInfo | null>(null)
  const recentWorkspaces = ref<WorkspaceInfo[]>([])
  const files = ref<FileEntry[]>([])
  const activePath = ref<string | null>(null)
  const content = ref('')
  const savedContent = ref('')
  const cursor = ref({ line: 1, col: 1 })
  const log = ref<string[]>([])
  const logVersion = ref(0)
  const runStatus = ref<RunStatus>('idle')
  const busy = ref(false)
  const services = ref<ServicesStatus>({
    configured: false,
    name: '未配置全栈运行',
    path: null,
    running: false,
    stopping: false,
    services: []
  })
  const serviceLogs = ref<ServiceLogEvent[]>([])
  const serviceLogVersion = ref(0)
  const selectedOutput = ref('all')
  const notice = ref('')
  const showNewProject = ref(false)

  const dirty = computed(() => content.value !== savedContent.value)
  const activeFile = computed(
    () => files.value.find((file) => file.path === activePath.value) ?? null
  )

  let disposeRun: (() => void) | null = null
  let disposeServices: (() => void) | null = null
  let initializing = false
  let autoPreviewOpened = false

  const stackActive = computed(
    () =>
      services.value.running ||
      services.value.services.some((service) =>
        ['building', 'starting', 'ready', 'stopping'].includes(service.state)
      )
  )

  const outputRunning = computed(() => busy.value || stackActive.value)

  const outputTabs = computed(() => {
    const tabs = [{ id: 'all', label: '全部', state: stackActive.value ? 'running' : 'idle' }]
    for (const service of services.value.services) {
      tabs.push({ id: service.id, label: service.name, state: service.state })
    }
    if (log.value.length > 0 || busy.value || runStatus.value !== 'idle') {
      tabs.push({ id: 'gradle', label: '工程任务', state: runStatus.value })
    }
    return tabs
  })

  const outputLines = computed(() => {
    if (selectedOutput.value === 'gradle') return log.value
    if (selectedOutput.value === 'all') {
      return serviceLogs.value.map((entry) => {
        const service = services.value.services.find((item) => item.id === entry.serviceId)
        return `[${service?.name ?? entry.serviceId}] ${entry.line}`
      })
    }
    return serviceLogs.value
      .filter((entry) => entry.serviceId === selectedOutput.value)
      .map((entry) => entry.line)
  })

  const outputVersion = computed(() =>
    selectedOutput.value === 'gradle' ? logVersion.value : serviceLogVersion.value
  )

  const previewUrl = computed(() => {
    if (selectedOutput.value !== 'all' && selectedOutput.value !== 'gradle') {
      const selected = services.value.services.find((item) => item.id === selectedOutput.value)
      if (selected?.openUrl) return selected.openUrl
    }
    return services.value.previewUrl ?? null
  })

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

  async function loadRecentWorkspaces(): Promise<void> {
    try {
      recentWorkspaces.value = await window.api.listRecentWorkspaces()
    } catch {
      recentWorkspaces.value = []
    }
  }

  function replaceServiceLogs(status: ServicesStatus): void {
    serviceLogs.value = status.services.flatMap((service) =>
      service.log.map((line) => ({ serviceId: service.id, line }))
    )
    if (serviceLogs.value.length > 4000) {
      serviceLogs.value = serviceLogs.value.slice(-4000)
    }
    serviceLogVersion.value++
  }

  function applyServicesStatus(status: ServicesStatus): void {
    services.value = status
    replaceServiceLogs(status)
    if (
      selectedOutput.value !== 'all' &&
      selectedOutput.value !== 'gradle' &&
      !status.services.some((service) => service.id === selectedOutput.value)
    ) {
      selectedOutput.value = 'all'
    }
  }

  function updateServiceState(event: ServiceStateEvent): void {
    const service = services.value.services.find((item) => item.id === event.serviceId)
    if (!service) return
    service.state = event.state
    if (event.message !== undefined) service.message = event.message
    if (event.readyUrl !== undefined) service.readyUrl = event.readyUrl
    if (event.openUrl !== undefined) service.openUrl = event.openUrl
    if (event.state === 'ready') void refreshFiles()
    if (event.state === 'ready' && event.openUrl && !autoPreviewOpened) {
      autoPreviewOpened = true
      void window.api.openExternal(event.openUrl)
    }
    if (event.state === 'failed' && event.message) notice.value = event.message
  }

  function appendServiceLog(event: ServiceLogEvent): void {
    serviceLogs.value.push(event)
    if (serviceLogs.value.length > 4000) {
      serviceLogs.value.splice(0, serviceLogs.value.length - 4000)
    }
    serviceLogVersion.value++
  }

  function connectServiceEvents(): void {
    disposeServices?.()
    disposeServices = api.subscribeServices({
      onSnapshot: applyServicesStatus,
      onState: updateServiceState,
      onLog: appendServiceLog,
      onError: () => {
        if (stackActive.value) notice.value = '服务日志连接中断，正在自动重连…'
      }
    })
  }

  async function loadServicesStatus(): Promise<void> {
    try {
      applyServicesStatus(await api.getServicesStatus())
    } catch (error) {
      notice.value = messageOf(error, '读取全栈运行配置失败')
    }
  }

  /** 工作区切换后的统一收尾：清空编辑状态、刷新树并打开首个 Java 文件。 */
  async function applyWorkspace(next: WorkspaceInfo): Promise<void> {
    workspace.value = next
    activePath.value = null
    content.value = ''
    savedContent.value = ''
    await refreshFiles()

    const first = files.value.find((file) => file.path.endsWith('.java')) ?? files.value[0]
    if (first) await openFile(first.path)
    await loadRecentWorkspaces()
    await loadServicesStatus()
  }

  async function init(restart = false): Promise<void> {
    if (initializing) return
    initializing = true
    try {
      const info = restart ? await window.api.restartBackend() : await waitForBackend()
      backend.value = info
      if (info.status !== 'ready' || !info.url || !info.token) {
        notice.value = info.message ?? '后端未就绪'
        return
      }

      notice.value = ''
      api.connect(info.url, info.token)
      connectServiceEvents()
      workspace.value = await window.api.getWorkspace()
      if (workspace.value) {
        await refreshFiles()
        const first = files.value.find((file) => file.path.endsWith('.java')) ?? files.value[0]
        if (first) await openFile(first.path)
      }
      await loadServicesStatus()
      await loadRecentWorkspaces()
    } catch (error) {
      notice.value = messageOf(error, '连接后端失败')
    } finally {
      initializing = false
    }
  }

  function retryBackend(): Promise<void> {
    return init(true)
  }

  function updateBackendStatus(info: BackendInfo): void {
    backend.value = info
    if (info.status === 'error' || info.status === 'missing') {
      notice.value = info.message ?? '后端不可用'
    }
  }

  function confirmDiscard(): boolean {
    return !dirty.value || window.confirm('当前文件有未保存的修改，确定放弃并继续？')
  }

  async function refreshFiles(): Promise<boolean> {
    try {
      files.value = await api.listFiles()
      return true
    } catch (error) {
      files.value = []
      notice.value = messageOf(error, '刷新文件列表失败')
      return false
    }
  }

  async function openFile(path: string): Promise<void> {
    if (path === activePath.value) return
    if (!confirmDiscard()) return
    try {
      const text = await api.readFile(path)
      activePath.value = path
      content.value = text
      savedContent.value = text
    } catch (error) {
      notice.value = messageOf(error, `无法打开文件 ${path}`)
    }
  }

  async function save(): Promise<boolean> {
    if (!activePath.value) return false
    try {
      await api.writeFile(activePath.value, content.value)
      savedContent.value = content.value
      notice.value = '已保存'
      return true
    } catch (error) {
      notice.value = messageOf(error, '保存失败')
      return false
    }
  }

  async function pickWorkspace(): Promise<void> {
    if (busy.value || stackActive.value) {
      notice.value = '请先停止当前任务或全栈服务再切换工作区'
      return
    }
    if (!confirmDiscard()) return
    const picked = await window.api.pickWorkspace()
    if (!picked) return
    await applyWorkspace(picked)
  }

  /** 从最近项目列表直接切换工作区，不再弹出目录选择框。 */
  async function switchWorkspace(path: string): Promise<void> {
    if (path === workspace.value?.path) return
    if (busy.value || stackActive.value) {
      notice.value = '请先停止当前任务或全栈服务再切换工作区'
      return
    }
    if (!confirmDiscard()) return

    const switched = await window.api.openWorkspace(path)
    if (!switched) {
      notice.value = '无法切换项目，请确认目录存在且后端已就绪'
      return
    }
    await applyWorkspace(switched)
  }

  /** 输出面板按行累积，最多保留 2000 行，避免长任务把内存和 DOM 拖垮 */
  function appendLog(line: string): void {
    log.value.push(line)
    if (log.value.length > 2000) log.value.splice(0, log.value.length - 2000)
    logVersion.value++
  }

  function clearLog(): void {
    log.value = []
    logVersion.value++
  }

  async function run(task: RunTask): Promise<void> {
    if (busy.value) return
    if (stackActive.value) {
      notice.value = '全栈服务运行中，请先停止全部服务'
      return
    }
    if (!workspace.value) {
      notice.value = '请先打开工作区'
      return
    }

    busy.value = true
    runStatus.value = 'running'
    log.value = []
    appendLog(`> gradle ${task} --console=plain -q`)

    try {
      disposeRun = api.runTask(task, {
        onLog: appendLog,
        onExit: (code) => {
          busy.value = false
          runStatus.value = code === '0' ? 'success' : 'failed'
          appendLog(`[进程退出，代码 ${code}]`)
          // Gradle 会更新 build 目录；任务结束后自动刷新文件树中的源码与产物。
          void refreshFiles()
        },
        onError: () => {
          busy.value = false
          runStatus.value = 'failed'
          appendLog('[输出连接中断]')
        }
      })
    } catch (error) {
      busy.value = false
      runStatus.value = 'failed'
      appendLog(`[无法启动构建：${messageOf(error, '后端未连接')}]`)
    }
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

  async function startAllServices(): Promise<void> {
    if (stackActive.value) return
    if (!services.value.configured) {
      notice.value = services.value.message ?? '当前项目未配置 .gmch/run.json'
      return
    }
    if (!workspace.value) {
      notice.value = '请先打开工作区'
      return
    }

    selectedOutput.value = 'all'
    autoPreviewOpened = false
    notice.value = `正在启动 ${services.value.name}…`
    try {
      applyServicesStatus(await api.startServices())
      notice.value = '正在构建并启动服务…'
    } catch (error) {
      notice.value = messageOf(error, '启动全栈服务失败')
      await loadServicesStatus()
    }
  }

  async function stopAllServices(): Promise<void> {
    if (!stackActive.value && !services.value.running) return
    notice.value = '正在停止全部服务…'
    try {
      applyServicesStatus(await api.stopServices())
      notice.value = '全栈服务已停止'
      await refreshFiles()
    } catch (error) {
      notice.value = messageOf(error, '停止全栈服务失败')
      await loadServicesStatus()
    }
  }

  function selectOutput(selection: string): void {
    selectedOutput.value = selection
  }

  function clearOutput(): void {
    if (selectedOutput.value === 'gradle') {
      clearLog()
      return
    }
    if (selectedOutput.value === 'all') {
      serviceLogs.value = []
    } else {
      serviceLogs.value = serviceLogs.value.filter(
        (entry) => entry.serviceId !== selectedOutput.value
      )
    }
    serviceLogVersion.value++
  }

  function openPreview(): void {
    if (previewUrl.value) void window.api.openExternal(previewUrl.value)
  }

  function disposeServiceEvents(): void {
    disposeServices?.()
    disposeServices = null
  }

  function setCursor(line: number, col: number): void {
    cursor.value = { line, col }
  }

  function openNewProject(): void {
    if (busy.value || stackActive.value) {
      notice.value = '请先停止当前任务或全栈服务再新建工程'
      return
    }
    if (!confirmDiscard()) return
    showNewProject.value = true
  }

  function closeNewProject(): void {
    showNewProject.value = false
  }

  async function loadProjectDefaults(): Promise<api.ProjectDefaults | null> {
    try {
      return await api.getProjectDefaults()
    } catch (error) {
      notice.value = messageOf(error, '无法读取默认工程目录')
      return null
    }
  }

  /**
   * 新建 Gradle Java 工程。后端负责生成工程与 Wrapper 并把工作区切过去，
   * 这里只刷新文件列表并打开 Main.java。
   * @returns 失败时的错误信息；成功返回 null
   */
  async function createProject(input: {
    parentDir: string
    name: string
    gradleVersion: string
  }): Promise<string | null> {
    if (busy.value || stackActive.value) return '请先停止当前任务或全栈服务再新建工程'
    try {
      const created = await api.createProject(input)
      const registered = await window.api.openWorkspace(created.path)
      workspace.value = registered ?? { path: created.path, name: created.name }
      activePath.value = null
      content.value = ''
      savedContent.value = ''
      const refreshed = await refreshFiles()
      if (!refreshed) return '工程已创建，但刷新文件列表失败'

      const main = files.value.find((file) => file.path.endsWith('Main.java'))
      if (main) await openFile(main.path)
      await loadRecentWorkspaces()

      showNewProject.value = false
      notice.value = `已创建工程 ${created.name}`
      return null
    } catch (error) {
      return messageOf(error, '创建工程失败')
    }
  }

  return {
    backend,
    workspace,
    recentWorkspaces,
    files,
    activePath,
    content,
    savedContent,
    cursor,
    log,
    logVersion,
    runStatus,
    busy,
    services,
    serviceLogVersion,
    selectedOutput,
    stackActive,
    outputRunning,
    outputTabs,
    outputLines,
    outputVersion,
    previewUrl,
    notice,
    dirty,
    activeFile,
    showNewProject,
    init,
    retryBackend,
    updateBackendStatus,
    refreshFiles,
    openFile,
    save,
    pickWorkspace,
    switchWorkspace,
    loadRecentWorkspaces,
    run,
    stop,
    startAllServices,
    stopAllServices,
    selectOutput,
    clearOutput,
    openPreview,
    disposeServiceEvents,
    clearLog,
    setCursor,
    openNewProject,
    closeNewProject,
    loadProjectDefaults,
    createProject
  }
})
