export type BackendStatus = 'stopped' | 'starting' | 'ready' | 'error' | 'missing'

export interface BackendInfo {
  status: BackendStatus
  url: string | null
  token: string | null
  message?: string
}

export interface WorkspaceInfo {
  path: string
  name: string
}

export interface FileEntry {
  path: string
  name: string
}

export interface EnvInfo {
  javaVersion: string
  javaHome: string
  os: string
  gradleWrapper: boolean
}

export interface AppInfo {
  version: string
  platform: string
  backend: BackendInfo
}

export type MenuAction = 'open-workspace' | 'save' | 'compile' | 'run' | 'stop'

export type RunTask = 'compileJava' | 'run'

export type RunStatus = 'idle' | 'running' | 'success' | 'failed'
