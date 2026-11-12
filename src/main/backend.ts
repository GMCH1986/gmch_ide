import { app } from 'electron'
import { spawn, type ChildProcess } from 'child_process'
import { randomUUID } from 'crypto'
import { existsSync, mkdirSync } from 'fs'
import { createServer, type AddressInfo } from 'net'
import { join } from 'path'
import type { BackendInfo } from '../shared/types'

/**
 * Java sidecar 的生命周期管理。
 * 端口由主进程分配、token 由主进程生成，两者通过启动参数传给 Spring Boot，
 * 再经 IPC 交给渲染进程；后端只监听 127.0.0.1。
 */

let child: ChildProcess | null = null
let current: BackendInfo = { status: 'stopped', url: null, token: null }
let stopping = false
let statusListener: ((info: BackendInfo) => void) | null = null

export function getBackendInfo(): BackendInfo {
  return current
}

/** 主进程通过该监听器把 sidecar 的启动、异常退出等状态同步给渲染进程 */
export function setBackendStatusListener(listener: (info: BackendInfo) => void): void {
  statusListener = listener
}

function updateCurrent(info: BackendInfo): void {
  current = info
  try {
    statusListener?.(current)
  } catch {
    // 渲染窗口关闭时状态推送失败不能影响 sidecar 生命周期
  }
}

/** 打包后位于 resources/backend；开发模式位于 build/backend */
function backendRoot(): string {
  return app.isPackaged
    ? join(process.resourcesPath, 'backend')
    : join(app.getAppPath(), 'build', 'backend')
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address() as AddressInfo
      server.close(() => resolve(address.port))
    })
  })
}

async function waitHealthy(
  process: ChildProcess,
  port: number,
  token: string,
  timeoutMs: number
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    // 进程已退出时无需继续等待
    if (child !== process || process.exitCode !== null || process.signalCode !== null) return false
    try {
      const response = await fetch(`http://127.0.0.1:${port}/api/health`, {
        headers: { 'X-GMCH-Token': token },
        signal: AbortSignal.timeout(2000)
      })
      if (response.ok) return true
    } catch {
      // 端口尚未监听，继续重试
    }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  return false
}

export async function startBackend(
  workspace: string | null,
  javaHome: string | null
): Promise<BackendInfo> {
  if (child) return current
  stopping = false

  const root = backendRoot()
  const java = join(root, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  const jar = join(root, 'app.jar')
  if (!existsSync(java) || !existsSync(jar)) {
    updateCurrent({
      status: 'missing',
      url: null,
      token: null,
      message: `后端未构建（缺少 ${root}）。请执行：npm run backend:build`
    })
    return current
  }

  const port = await freePort()
  const token = randomUUID()
  updateCurrent({ status: 'starting', url: `http://127.0.0.1:${port}`, token })

  // 后端所有临时文件都放到 userData 下：既避免污染系统 temp，
  // 也规避部分机器上 %TEMP% 权限异常导致 Tomcat 无法创建 work 目录
  const tmpDir = join(app.getPath('userData'), 'backend-tmp')
  mkdirSync(tmpDir, { recursive: true })

  const args = [
    `-Djava.io.tmpdir=${tmpDir}`,
    '-Dfile.encoding=UTF-8',
    '-jar',
    jar,
    `--server.port=${port}`,
    `--app.token=${token}`
  ]
  if (workspace) args.push(`--app.workspace=${workspace}`)
  // 用户 JDK 供 Gradle 构建使用；sidecar 自带的 jlink 运行时不含 javac
  if (javaHome) args.push(`--app.java-home=${javaHome}`)

  const proc = spawn(java, args, {
    cwd: root,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe']
  })
  child = proc
  proc.stdout?.on('data', (chunk: Buffer) => process.stdout.write(`[backend] ${chunk}`))
  proc.stderr?.on('data', (chunk: Buffer) => process.stderr.write(`[backend] ${chunk}`))
  proc.on('error', (error) => {
    if (child !== proc) return
    child = null
    updateCurrent({
      status: 'error',
      url: null,
      token: null,
      message: `后端进程启动失败：${error.message}`
    })
  })
  proc.on('exit', (code, signal) => {
    // stopBackend 会主动清空 child；旧进程的迟到事件不能覆盖新进程状态
    if (child !== proc) return
    child = null
    if (!stopping) {
      updateCurrent({
        status: 'error',
        url: null,
        token: null,
        message: `后端进程退出（code=${code ?? 'null'}, signal=${signal ?? 'null'}）`
      })
    }
  })

  const healthy = await waitHealthy(proc, port, token, 30_000)
  const stillRunning = child === proc && proc.exitCode === null && proc.signalCode === null
  if (healthy && stillRunning) {
    updateCurrent({ status: 'ready', url: `http://127.0.0.1:${port}`, token })
    return current
  }

  if (child === proc) {
    child = null
    proc.kill()
    updateCurrent({
      status: 'error',
      url: null,
      token: null,
      message: healthy ? '后端在启动过程中退出' : '后端启动超时，请查看控制台日志'
    })
  }
  return current
}

export function stopBackend(): void {
  stopping = true
  const proc = child
  child = null
  updateCurrent({ status: 'stopped', url: null, token: null })
  if (!proc) return

  proc.kill('SIGTERM')
  const timer = setTimeout(() => {
    if (proc.exitCode === null && proc.signalCode === null) proc.kill('SIGKILL')
  }, 3000)
  timer.unref()
}

/** 重新启动已退出或启动失败的 sidecar；旧进程事件由 child 身份检查隔离 */
export async function restartBackend(
  workspace: string | null,
  javaHome: string | null
): Promise<BackendInfo> {
  stopBackend()
  return startBackend(workspace, javaHome)
}

/** 切换工作区：后端持有根目录，切换后无需重启 sidecar */
export async function setWorkspacePath(path: string): Promise<boolean> {
  if (current.status !== 'ready' || !current.url || !current.token) return false
  try {
    const response = await fetch(`${current.url}/api/workspace`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-GMCH-Token': current.token },
      body: JSON.stringify({ path })
    })
    return response.ok
  } catch {
    return false
  }
}
