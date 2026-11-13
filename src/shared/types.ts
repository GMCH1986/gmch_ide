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
  binary: boolean
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

export type MenuAction = 'open-workspace' | 'new-project' | 'save' | 'compile' | 'run' | 'stop'

export type RunTask = 'compileJava' | 'run'

export type RunStatus = 'idle' | 'running' | 'success' | 'failed'

export type ServiceState =
  | 'idle'
  | 'building'
  | 'starting'
  | 'ready'
  | 'stopping'
  | 'stopped'
  | 'failed'

export interface ServiceStatus {
  id: string
  name: string
  state: ServiceState
  readyUrl?: string
  openUrl?: string
  message?: string
  log: string[]
}

export interface ServicesStatus {
  configured: boolean
  name: string
  path: string | null
  running: boolean
  stopping: boolean
  previewUrl?: string | null
  message?: string
  services: ServiceStatus[]
}

export interface ServiceStateEvent {
  serviceId: string
  state: ServiceState
  message?: string
  readyUrl?: string
  openUrl?: string
}

export interface ServiceLogEvent {
  serviceId: string
  line: string
}
