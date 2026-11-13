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

export interface StartupClassInfo {
  className: string
  packageName: string
  path: string
  springBoot: boolean
  buildSystem: 'gradle' | 'maven' | 'unknown'
  modulePath: string
}

export interface StartupClassesResult {
  found: boolean
  primary: StartupClassInfo | null
  candidates: StartupClassInfo[]
}

export type BuildSystem = 'maven' | 'gradle'

export interface BuildToolCommand {
  id: string
  label: string
  task: string
  description: string
  executable: boolean
  disabledReason: string | null
  commandLine: string
}

export interface BuildToolModule {
  id: string
  name: string
  path: string
  taskPath: string
  commands: BuildToolCommand[]
}

export interface BuildToolProject {
  id: string
  system: BuildSystem
  label: string
  rootPath: string
  wrapper: string | null
  wrapperAvailable: boolean
  modules: BuildToolModule[]
}

export interface BuildToolsResult {
  projects: BuildToolProject[]
}

export interface BuildCommandTarget {
  system: BuildSystem
  projectPath: string
  modulePath: string
  task: string
}