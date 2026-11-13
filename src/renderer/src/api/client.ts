import type {
  EnvInfo,
  FileEntry,
  RunTask,
  ServiceLogEvent,
  ServicesStatus,
  ServiceStateEvent
} from '../../../shared/types'

interface Connection {
  url: string
  token: string
}

let connection: Connection | null = null

/** 由 store 在拿到主进程分配的 base URL 与 token 后调用 */
export function connect(url: string, token: string): void {
  connection = { url, token }
}

function endpoint(path: string, withToken = false): string {
  if (!connection) throw new Error('后端未连接')
  const url = new URL(path, connection.url)
  if (withToken) url.searchParams.set('token', connection.token)
  return url.toString()
}

function headers(json = false): Record<string, string> {
  if (!connection) throw new Error('后端未连接')
  return {
    'X-GMCH-Token': connection.token,
    ...(json ? { 'Content-Type': 'application/json' } : {})
  }
}

async function parse<T>(response: Response): Promise<T> {
  if (!response.ok) throw new Error(`请求失败：${response.status}`)
  return (await response.json()) as T
}

export async function listFiles(): Promise<FileEntry[]> {
  return parse<FileEntry[]>(await fetch(endpoint('/api/files'), { headers: headers() }))
}

export async function readFile(path: string): Promise<string> {
  const response = await fetch(endpoint(`/api/file?path=${encodeURIComponent(path)}`), {
    headers: headers()
  })
  if (!response.ok) throw new Error(`读取失败：${response.status}`)
  return response.text()
}

export async function writeFile(path: string, content: string): Promise<void> {
  const response = await fetch(endpoint(`/api/file?path=${encodeURIComponent(path)}`), {
    method: 'PUT',
    headers: { ...headers(), 'Content-Type': 'text/plain' },
    body: content
  })
  if (!response.ok) throw new Error(`保存失败：${response.status}`)
}

export async function getEnv(): Promise<EnvInfo> {
  return parse<EnvInfo>(await fetch(endpoint('/api/env'), { headers: headers() }))
}

export async function getWorkspace(): Promise<{ path: string; name: string } | null> {
  const response = await fetch(endpoint('/api/workspace'), { headers: headers() })
  if (!response.ok) return null
  const data = (await response.json()) as { path?: string; name?: string }
  return data.path ? { path: data.path, name: data.name ?? data.path } : null
}

export interface RunHandlers {
  onLog: (line: string) => void
  onExit: (code: string) => void
  onError: () => void
}

/**
 * 构建/运行输出走 SSE：EventSource 不能带自定义头，因此 token 走查询参数。
 * 返回值为关闭函数，用于「停止」时立即断开本地连接。
 */
export function runTask(task: RunTask, handlers: RunHandlers): () => void {
  const source = new EventSource(endpoint(`/api/run?task=${encodeURIComponent(task)}`, true))

  source.addEventListener('log', (event) => {
    handlers.onLog((event as MessageEvent<string>).data)
  })
  source.addEventListener('exit', (event) => {
    handlers.onExit((event as MessageEvent<string>).data)
    source.close()
  })
  source.onerror = (): void => {
    handlers.onError()
    source.close()
  }

  return () => source.close()
}

export async function stopRun(): Promise<void> {
  await fetch(endpoint('/api/run/stop'), { method: 'POST', headers: headers() })
}

export async function getServicesStatus(): Promise<ServicesStatus> {
  return parse<ServicesStatus>(
    await fetch(endpoint('/api/services'), { headers: headers() })
  )
}

export async function startServices(): Promise<ServicesStatus> {
  const response = await fetch(endpoint('/api/services/start'), {
    method: 'POST',
    headers: headers()
  })
  if (!response.ok) {
    let message = `启动全栈失败：${response.status}`
    try {
      const data = (await response.json()) as { message?: string }
      if (data.message) message = data.message
    } catch {
      // 非 JSON 错误体，保留状态码
    }
    throw new Error(message)
  }
  return (await response.json()) as ServicesStatus
}

export async function stopServices(): Promise<ServicesStatus> {
  return parse<ServicesStatus>(
    await fetch(endpoint('/api/services/stop'), {
      method: 'POST',
      headers: headers()
    })
  )
}

export interface ServicesEventHandlers {
  onSnapshot: (status: ServicesStatus) => void
  onState: (event: ServiceStateEvent) => void
  onLog: (event: ServiceLogEvent) => void
  onError: () => void
}

/** 全栈运行事件流；EventSource 会自动重连，返回函数用于页面卸载时主动释放。 */
export function subscribeServices(handlers: ServicesEventHandlers): () => void {
  const source = new EventSource(endpoint('/api/services/events', true))
  const parseEvent = <T>(event: Event): T => JSON.parse((event as MessageEvent<string>).data) as T

  source.addEventListener('snapshot', (event) => {
    handlers.onSnapshot(parseEvent<ServicesStatus>(event))
  })
  source.addEventListener('state', (event) => {
    handlers.onState(parseEvent<ServiceStateEvent>(event))
  })
  source.addEventListener('log', (event) => {
    handlers.onLog(parseEvent<ServiceLogEvent>(event))
  })
  source.onerror = (): void => handlers.onError()

  return () => source.close()
}

export interface ProjectDefaults {
  parentDir: string
  name: string
  gradleVersions: string[]
}

export async function getProjectDefaults(): Promise<ProjectDefaults> {
  return parse<ProjectDefaults>(
    await fetch(endpoint('/api/project/defaults'), { headers: headers() })
  )
}

export async function createProject(input: {
  parentDir: string
  name: string
  gradleVersion: string
}): Promise<{ path: string; name: string }> {
  const response = await fetch(endpoint('/api/project'), {
    method: 'POST',
    headers: { ...headers(), 'Content-Type': 'application/json' },
    body: JSON.stringify(input)
  })
  if (!response.ok) {
    let message = `创建失败：${response.status}`
    try {
      const data = (await response.json()) as { message?: string }
      if (data.message) message = data.message
    } catch {
      // 非 JSON 错误体，保留状态码
    }
    throw new Error(message)
  }
  return (await response.json()) as { path: string; name: string }
}
