import { contextBridge, ipcRenderer } from 'electron'
import type { AppInfo, BackendInfo, MenuAction, WorkspaceInfo } from '../shared/types'

/**
 * 能力白名单：渲染进程只能通过这里暴露的方法触达主进程，
 * 不暴露通用 ipcRenderer / process。
 */
const api = {
  platform: process.platform,

  getInfo: (): Promise<AppInfo> => ipcRenderer.invoke('app:get-info'),
  getBackend: (): Promise<BackendInfo> => ipcRenderer.invoke('app:backend'),
  getWorkspace: (): Promise<WorkspaceInfo | null> => ipcRenderer.invoke('workspace:get'),
  pickWorkspace: (): Promise<WorkspaceInfo | null> => ipcRenderer.invoke('workspace:pick'),

  openExternal: (url: string): Promise<void> => ipcRenderer.invoke('shell:open-external', url),
  reveal: (path: string): Promise<void> => ipcRenderer.invoke('shell:reveal', path),
  closeWindow: (): Promise<void> => ipcRenderer.invoke('app:close'),

  onMenuAction: (callback: (action: MenuAction) => void): (() => void) => {
    const listener = (_event: unknown, action: MenuAction): void => callback(action)
    ipcRenderer.on('menu:action', listener)
    return () => ipcRenderer.removeListener('menu:action', listener)
  },

  onCloseRequest: (callback: () => void): (() => void) => {
    const listener = (): void => callback()
    ipcRenderer.on('app:close-request', listener)
    return () => ipcRenderer.removeListener('app:close-request', listener)
  },

  onBackendStatus: (callback: (info: BackendInfo) => void): (() => void) => {
    const listener = (_event: unknown, info: BackendInfo): void => callback(info)
    ipcRenderer.on('backend:status', listener)
    return () => ipcRenderer.removeListener('backend:status', listener)
  }
}

export type ExposedAPI = typeof api

contextBridge.exposeInMainWorld('api', api)
