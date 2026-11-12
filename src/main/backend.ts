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

export function getBackendInfo(): BackendInfo {
  return current
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

async function waitHealthy(port: number, token: string, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    // 进程已退出时无需继续等待
    if (!child) return false
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

export async function startBackend(workspace: string | null): Promise<BackendInfo> {
  if (child) return current
  stopping = false

  const root = backendRoot()
  const java = join(root, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  const jar = join(root, 'app.jar')
  if (!existsSync(java) || !existsSync(jar)) {
    current = {
      status: 'missing',
      url: null,
      token: null,
      message: `后端未构建（缺少 ${root}）。请执行：npm run backend:build`
    }
    return current
  }

  const port = await freePort()
  const token = randomUUID()
  current = { status: 'starting', url: `http://127.0.0.1:${port}`, token }

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
  if (process.env.JAVA_HOME) args.push(`--app.java-home=${process.env.JAVA_HOME}`)

  child = spawn(java, args, {
    cwd: root,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe']
  })
  child.stdout?.on('data', (chunk: Buffer) => process.stdout.write(`[backend] ${chunk}`))
  child.stderr?.on('data', (chunk: Buffer) => process.stderr.write(`[backend] ${chunk}`))
  child.on('exit', (code) => {
    child = null
    if (!stopping) {
      current = {
        status: 'error',
        url: null,
        token: null,
        message: `后端进程退出（code=${code}）`
      }
    }
  })

  const healthy = await waitHealthy(port, token, 30_000)
  if (!healthy) {
    child?.kill()
    child = null
    current = { status: 'error', url: null, token: null, message: '后端启动超时，请查看控制台日志' }
    return current
  }

  current = { status: 'ready', url: `http://127.0.0.1:${port}`, token }
  return current
}

export function stopBackend(): void {
  stopping = true
  const proc = child
  child = null
  current = { status: 'stopped', url: null, token: null }
  if (!proc) return

  proc.kill('SIGTERM')
  const timer = setTimeout(() => {
    if (!proc.killed) proc.kill('SIGKILL')
  }, 3000)
  timer.unref()
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
