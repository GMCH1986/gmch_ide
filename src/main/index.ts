import { app, BrowserWindow, dialog, ipcMain, Menu, shell } from 'electron'
import { existsSync, readFileSync, writeFileSync } from 'fs'
import { join } from 'path'
import type { AppInfo, MenuAction, WorkspaceInfo } from '../shared/types'
import { getBackendInfo, setWorkspacePath, startBackend, stopBackend } from './backend'

interface Settings {
  workspace: string | null
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

function workspaceInfo(): WorkspaceInfo | null {
  if (!workspace) return null
  const name = workspace.split(/[\\/]/).filter(Boolean).pop() ?? workspace
  return { path: workspace, name }
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
    mainWindow?.show()
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
}

function registerIpc(): void {
  ipcMain.handle('app:get-info', (): AppInfo => {
    return { version: app.getVersion(), platform: process.platform, backend: getBackendInfo() }
  })

  ipcMain.handle('app:backend', () => getBackendInfo())

  ipcMain.handle('workspace:get', () => workspaceInfo())

  ipcMain.handle('workspace:pick', async (): Promise<WorkspaceInfo | null> => {
    if (!mainWindow) return null
    const result = await dialog.showOpenDialog(mainWindow, {
      title: '选择 Gradle 工程目录',
      properties: ['openDirectory']
    })
    if (result.canceled || !result.filePaths[0]) return null

    const selected = result.filePaths[0]
    if (!(await setWorkspacePath(selected))) {
      dialog.showErrorBox('无法打开工作区', '后端未就绪或目录不可访问，请稍后重试。')
      return null
    }
    workspace = selected
    writeJson(settingsFile(), { workspace })
    return workspaceInfo()
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
}

const gotLock = app.requestSingleInstanceLock()

if (!gotLock) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (!mainWindow) return
    if (mainWindow.isMinimized()) mainWindow.restore()
    mainWindow.focus()
  })

  app.whenReady().then(() => {
    app.setAppUserModelId('com.gmch.ide')

    const settings = readJson<Settings>(settingsFile(), { workspace: null })
    workspace = settings.workspace && existsSync(settings.workspace) ? settings.workspace : null

    buildMenu()
    registerIpc()
    createWindow()

    void startBackend(workspace).then((info) => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('backend:status', info)
      }
    })

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow()
    })
  })

  app.on('before-quit', () => {
    allowClose = true
    saveWindowState()
    stopBackend()
  })

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit()
  })
}
