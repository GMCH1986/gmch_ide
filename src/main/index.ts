import { app, BrowserWindow, dialog, ipcMain, Menu, shell } from 'electron'
import { execFileSync } from 'child_process'
import {
  appendFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync
} from 'fs'
import { dirname, isAbsolute, join } from 'path'
import type { AppInfo, BackendInfo, MenuAction, WorkspaceInfo } from '../shared/types'
import {
  getBackendInfo,
  restartBackend,
  setBackendStatusListener,
  setWorkspacePath,
  startBackend,
  stopBackend
} from './backend'

/**
 * 启动诊断日志：写到 %APPDATA%\gmch-ide\startup.log。
 * 打包后双击无反应时，这是唯一能拿到的现场（GUI 进程没有控制台输出）。
 */
function bootLog(message: string): void {
  try {
    const base = process.env.APPDATA ?? app.getPath('appData')
    const dir = join(base, 'gmch-ide')
    mkdirSync(dir, { recursive: true })
    appendFileSync(join(dir, 'startup.log'), `${new Date().toISOString()} ${message}\n`, 'utf-8')
  } catch {
    // 日志失败不能影响启动
  }
}

bootLog(`boot pid=${process.pid} argv=${process.argv.slice(1).join(' ')}`)

/**
 * 找到一个「带编译器」的 JDK（不是 JRE）：Gradle 构建需要 javac。
 * 优先 JAVA_HOME，其次常见安装目录，最后 PATH 上的 javac。
 */
function resolveJavaHome(): string | null {
  const isWin = process.platform === 'win32'
  const javacName = isWin ? 'javac.exe' : 'javac'
  const hasCompiler = (home: string): boolean => existsSync(join(home, 'bin', javacName))
  const compilerMajor = (home: string): number | null => {
    try {
      const release = readFileSync(join(home, 'release'), 'utf-8')
      const match = release.match(/^JAVA_VERSION="(?:1\.)?(\d+)/m)
      return match ? Number(match[1]) : null
    } catch {
      return null
    }
  }

  const candidates: string[] = []
  if (process.env.JAVA_HOME) candidates.push(process.env.JAVA_HOME)

  const roots = [
    // 开发环境与 doctor/build 脚本共用仓库内便携 JDK
    join(app.getAppPath(), '.tools'),
    ...(isWin
      ? [
          'C:\\Program Files\\Java',
          'C:\\Program Files\\Eclipse Adoptium',
          'C:\\Program Files\\Microsoft',
          'C:\\Program Files\\Zulu',
          join(process.env.USERPROFILE ?? '', '.jdks')
        ]
      : [])
  ]
  for (const root of roots) {
    try {
      for (const name of readdirSync(root)) candidates.push(join(root, name))
    } catch {
      // 目录不存在时忽略
    }
  }

  for (const home of candidates) {
    const major = compilerMajor(home)
    if (hasCompiler(home) && (major === null || major >= 17)) return home
  }

  try {
    const command = isWin ? 'where.exe' : 'which'
    const output = execFileSync(command, ['javac'], { encoding: 'utf-8', timeout: 3000 })
    const first = output
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find(Boolean)
    if (first) {
      const home = dirname(dirname(first))
      const major = compilerMajor(home)
      if (hasCompiler(home) && (major === null || major >= 17)) return home
    }
  } catch {
    // PATH 上没有 javac
  }

  return null
}

const javaHome = resolveJavaHome()
bootLog(`jdk=${javaHome ?? 'not-found'}`)

process.on('uncaughtException', (error) => {
  bootLog(`uncaught: ${error.stack ?? error.message}`)
  dialog.showErrorBox('GMCH IDE 启动失败', error.stack ?? error.message)
  app.exit(1)
})

process.on('unhandledRejection', (reason) => {
  bootLog(`unhandledRejection: ${String(reason)}`)
})

app.on('child-process-gone', (_event, details) => {
  bootLog(
    `child-process-gone type=${details.type} reason=${details.reason} exitCode=${details.exitCode}`
  )
})

interface Settings {
  workspace: string | null
  recentWorkspaces?: string[]
}

interface WindowState {
  width: number
  height: number
  x?: number
  y?: number
  isMaximized?: boolean
}

const DEFAULT_WINDOW: WindowState = { width: 1280, height: 800 }

let mainWindow: BrowserWindow | null = null
let workspace: string | null = null
let recentWorkspaces: string[] = []
/** 关闭守卫放行标志：为真时窗口可以直接关闭 */
let allowClose = false
/** 关闭守卫等待渲染进程确认的兜底定时器 */
let closeGuardTimer: ReturnType<typeof setTimeout> | null = null

function clearCloseGuardTimer(): void {
  if (closeGuardTimer) {
    clearTimeout(closeGuardTimer)
    closeGuardTimer = null
  }
}

function settingsFile(): string {
  return join(app.getPath('userData'), 'settings.json')
}

function windowStateFile(): string {
  return join(app.getPath('userData'), 'window-state.json')
}

function readJson<T>(file: string, fallback: T): T {
  try {
    return existsSync(file) ? (JSON.parse(readFileSync(file, 'utf-8')) as T) : fallback
  } catch {
    return fallback
  }
}

function writeJson(file: string, data: unknown): void {
  try {
    writeFileSync(file, JSON.stringify(data, null, 2), 'utf-8')
  } catch (error) {
    console.error('Failed to write json:', file, error)
  }
}

function isDirectory(path: string): boolean {
  try {
    return statSync(path).isDirectory()
  } catch {
    return false
  }
}

function workspaceInfoFor(path: string): WorkspaceInfo {
  const name = path.split(/[\\/]/).filter(Boolean).pop() ?? path
  return { path, name }
}

function workspaceInfo(): WorkspaceInfo | null {
  return workspace ? workspaceInfoFor(workspace) : null
}

function samePath(a: string, b: string): boolean {
  return process.platform === 'win32'
    ? a.localeCompare(b, undefined, { sensitivity: 'accent' }) === 0
    : a === b
}

function availableRecentWorkspaces(): WorkspaceInfo[] {
  return recentWorkspaces.filter(isDirectory).map(workspaceInfoFor)
}

function persistWorkspaceSettings(): void {
  writeJson(settingsFile(), { workspace, recentWorkspaces })
}

function rememberWorkspace(path: string): void {
  recentWorkspaces = [path, ...recentWorkspaces.filter((item) => !samePath(item, path))].slice(
    0,
    8
  )
}

async function activateWorkspace(path: string): Promise<WorkspaceInfo | null> {
  if (!isAbsolute(path) || !isDirectory(path)) return null
  if (!(await setWorkspacePath(path))) return null

  workspace = path
  rememberWorkspace(path)
  persistWorkspaceSettings()
  return workspaceInfo()
}

function sendMenuAction(action: MenuAction): void {
  mainWindow?.webContents.send('menu:action', action)
}

function buildMenu(): void {
  const isMac = process.platform === 'darwin'
  const template: Electron.MenuItemConstructorOptions[] = [
    ...(isMac ? [{ role: 'appMenu' as const }] : []),
    {
      label: '文件',
      submenu: [
        {
          label: '打开文件夹…',
          accelerator: 'CmdOrCtrl+O',
          click: (): void => sendMenuAction('open-workspace')
        },
        {
          label: '新建工程…',
          accelerator: 'CmdOrCtrl+Shift+N',
          click: (): void => sendMenuAction('new-project')
        },
        { label: '保存', accelerator: 'CmdOrCtrl+S', click: (): void => sendMenuAction('save') },
        { type: 'separator' },
        isMac ? { role: 'close' } : { role: 'quit' }
      ]
    },
    {
      label: '构建',
      submenu: [
        { label: '编译', accelerator: 'F7', click: (): void => sendMenuAction('compile') },
        { label: '运行', accelerator: 'F5', click: (): void => sendMenuAction('run') },
        { label: '停止', accelerator: 'Shift+F5', click: (): void => sendMenuAction('stop') }
      ]
    },
    {
      label: '视图',
      submenu: [
        { role: 'reload' },
        { role: 'toggleDevTools' },
        { type: 'separator' },
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' }
      ]
    }
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

function loadWindowState(): WindowState {
  const state = readJson<WindowState>(windowStateFile(), DEFAULT_WINDOW)
  if (typeof state.width !== 'number' || typeof state.height !== 'number') {
    return { ...DEFAULT_WINDOW }
  }
  return {
    width: Math.max(960, state.width),
    height: Math.max(640, state.height),
    x: typeof state.x === 'number' ? state.x : undefined,
    y: typeof state.y === 'number' ? state.y : undefined,
    isMaximized: state.isMaximized === true
  }
}

function saveWindowState(): void {
  if (!mainWindow || mainWindow.isDestroyed()) return
  const bounds = mainWindow.getNormalBounds()
  writeJson(windowStateFile(), {
    width: bounds.width,
    height: bounds.height,
    x: bounds.x,
    y: bounds.y,
    isMaximized: mainWindow.isMaximized()
  })
}

function createWindow(): void {
  const state = loadWindowState()
  // macOS 关闭窗口后应用仍驻留，Dock 重新开窗时必须复位放行标志，
  // 否则新窗口的关闭会跳过未保存确认
  allowClose = false
  clearCloseGuardTimer()
  mainWindow = new BrowserWindow({
    width: state.width,
    height: state.height,
    ...(state.x !== undefined && state.y !== undefined ? { x: state.x, y: state.y } : {}),
    minWidth: 960,
    minHeight: 640,
    show: false,
    autoHideMenuBar: true,
    backgroundColor: '#1e1e24',
    title: 'GMCH IDE',
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false
    }
  })

  if (state.isMaximized) mainWindow.maximize()

  mainWindow.on('ready-to-show', () => {
    bootLog('window ready-to-show')
    mainWindow?.show()
  })

  mainWindow.webContents.on('did-finish-load', () => {
    bootLog('renderer did-finish-load')
  })

  mainWindow.webContents.on('did-fail-load', (_event, code, description, url) => {
    bootLog(`renderer did-fail-load code=${code} ${description} url=${url}`)
  })

  mainWindow.webContents.on('render-process-gone', (_event, details) => {
    bootLog(`renderer gone reason=${details.reason} exitCode=${details.exitCode}`)
  })

  // 关闭守卫：交给渲染进程确认未保存内容后再真正关闭。
  // 渲染进程卡死时用原生对话框兜底，避免主进程永久存活、阻挡安装/卸载。
  mainWindow.on('close', (event) => {
    if (allowClose) return
    event.preventDefault()

    if (!closeGuardTimer) {
      const win = mainWindow
      closeGuardTimer = setTimeout(() => {
        closeGuardTimer = null
        if (!win || win.isDestroyed() || allowClose) return
        const choice = dialog.showMessageBoxSync(win, {
          type: 'warning',
          buttons: ['取消', '强制退出'],
          defaultId: 0,
          cancelId: 0,
          title: 'GMCH IDE',
          message: '界面未响应，是否强制退出？',
          detail: '强制退出会丢失未保存的修改。'
        })
        if (choice === 1) {
          allowClose = true
          win.destroy()
        }
      }, 10_000)
    }

    mainWindow?.webContents.send('app:close-request')
  })

  mainWindow.on('closed', () => {
    clearCloseGuardTimer()
    mainWindow = null
  })

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) void shell.openExternal(url)
    return { action: 'deny' }
  })

  // electron-vite 只在开发模式注入该变量，用它判断 dev 比 is.dev 少一个依赖
  const devUrl = process.env['ELECTRON_RENDERER_URL']

  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (devUrl && url.startsWith(devUrl)) return
    event.preventDefault()
  })

  if (devUrl) {
    void mainWindow.loadURL(devUrl)
  } else {
    void mainWindow.loadFile(join(__dirname, '../renderer/index.html'))
  }

  // ready-to-show 依赖渲染进程首帧；渲染进程异常时窗口会永远隐藏，
  // 表现为「双击没反应」。这里加兜底，到点强制显示（哪怕空白）。
  setTimeout(() => {
    if (!mainWindow || mainWindow.isDestroyed()) return
    if (!mainWindow.isVisible()) {
      bootLog('force show: ready-to-show did not fire in 2.5s')
      mainWindow.show()
    }
  }, 2500)
}

function registerIpc(): void {
  ipcMain.handle('app:get-info', (): AppInfo => {
    return { version: app.getVersion(), platform: process.platform, backend: getBackendInfo() }
  })

  ipcMain.handle('app:backend', () => getBackendInfo())

  ipcMain.handle('workspace:get', () => workspaceInfo())

  ipcMain.handle('workspace:recent', () => availableRecentWorkspaces())

  ipcMain.handle('workspace:open', async (_event, path: unknown): Promise<WorkspaceInfo | null> => {
    if (typeof path !== 'string') return null
    return activateWorkspace(path)
  })

  ipcMain.handle('workspace:pick-directory', async (): Promise<string | null> => {
    if (!mainWindow) return null
    try {
      bootLog('pick-directory: opening dialog')
      // 不传父窗口：Windows 上 Electron 解析父 HWND 存在已知崩溃（0xc000041d），
      // 参见 electron#45673 / PR#53583；无父窗口的对话框更稳定
      const result = await dialog.showOpenDialog({
        title: '选择工程存放目录',
        defaultPath: workspace ?? undefined,
        properties: ['openDirectory']
      })
      bootLog(`pick-directory: canceled=${result.canceled}`)
      return result.canceled ? null : (result.filePaths[0] ?? null)
    } catch (error) {
      bootLog(`pick-directory failed: ${String(error)}`)
      return null
    }
  })

  ipcMain.handle('workspace:pick', async (): Promise<WorkspaceInfo | null> => {
    if (!mainWindow) return null
    try {
      bootLog('pick-workspace: opening dialog')
      const result = await dialog.showOpenDialog({
        title: '选择 Gradle 工程目录',
        defaultPath: workspace ?? undefined,
        properties: ['openDirectory']
      })
      bootLog(`pick-workspace: canceled=${result.canceled}`)
      if (result.canceled || !result.filePaths[0]) return null

      const opened = await activateWorkspace(result.filePaths[0])
      if (!opened) {
        dialog.showErrorBox('无法打开工作区', '后端未就绪或目录不可访问，请稍后重试。')
      }
      return opened
    } catch (error) {
      bootLog(`pick-workspace failed: ${String(error)}`)
      return null
    }
  })

  ipcMain.handle('shell:open-external', (_event, url: string) => {
    if (typeof url === 'string' && /^https?:/i.test(url)) void shell.openExternal(url)
  })

  ipcMain.handle('shell:reveal', (_event, path: string) => {
    if (typeof path === 'string' && existsSync(path)) shell.showItemInFolder(path)
  })

  ipcMain.handle('app:close', () => {
    clearCloseGuardTimer()
    allowClose = true
    saveWindowState()
    mainWindow?.close()
  })

  ipcMain.handle('app:close-cancel', () => {
    clearCloseGuardTimer()
  })

  ipcMain.handle('app:backend-restart', async (): Promise<BackendInfo> => {
    return restartBackend(workspace, javaHome)
  })
}

const gotLock = app.requestSingleInstanceLock()
bootLog(`singleInstanceLock=${gotLock}`)

if (!gotLock) {
  bootLog('quit: another instance holds the lock')
  app.quit()
} else {
  app.on('second-instance', () => {
    bootLog('second-instance received')
    if (!mainWindow) return
    if (mainWindow.isMinimized()) mainWindow.restore()
    mainWindow.show()
    mainWindow.focus()
  })

  app.whenReady().then(() => {
    bootLog('app ready')
    app.setAppUserModelId('com.gmch.ide')

    const settings = readJson<Settings>(settingsFile(), { workspace: null })
    workspace = settings.workspace && isDirectory(settings.workspace) ? settings.workspace : null
    recentWorkspaces = (settings.recentWorkspaces ?? []).filter(
      (path): path is string => typeof path === 'string' && isDirectory(path)
    )
    if (workspace) rememberWorkspace(workspace)

    buildMenu()
    registerIpc()
    createWindow()
    bootLog('window created')

    setBackendStatusListener((info) => {
      if (mainWindow && !mainWindow.isDestroyed() && !mainWindow.webContents.isDestroyed()) {
        mainWindow.webContents.send('backend:status', info)
      }
    })

    void startBackend(workspace, javaHome).then((info) => {
      bootLog(`backend status=${info.status} ${info.message ?? ''}`)
    })

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow()
    })
  })

  app.on('before-quit', () => {
    saveWindowState()
  })

  app.on('will-quit', () => {
    stopBackend()
  })

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit()
  })
}
