#!/usr/bin/env node
/**
 * 后端冒烟（自动化部分）：
 *   1. 用 jlink 运行时启动 sidecar
 *   2. 校验健康检查、token 鉴权、工作区与文件 API
 *   3. 通过 SSE 执行 compileJava，断言退出码为 0
 *
 * 用法：node scripts/smoke-backend.mjs [workspace]
 * 默认使用 examples/hello-java，需要本机具备 JDK 21（Gradle 构建用）。
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const backend = join(root, 'build', 'backend')
const isWin = process.platform === 'win32'
const java = join(backend, 'runtime', 'bin', isWin ? 'java.exe' : 'java')
const jar = join(backend, 'app.jar')
const workspace = process.argv[2] ?? join(root, 'examples', 'hello-java')
const token = `smoke-${Date.now()}`
const port = 18090 + Math.floor(Math.random() * 50)
const base = `http://127.0.0.1:${port}`
const tmpDir = join(root, '.tmp', 'smoke')
mkdirSync(tmpDir, { recursive: true })

/** 与 build-backend 一致的 JDK 解析：JAVA_HOME 优先，其次 .tools 便携 JDK */
function resolveJavaHome() {
  if (process.env.JAVA_HOME) return process.env.JAVA_HOME
  const tools = join(root, '.tools')
  if (!existsSync(tools)) return ''
  const dir = readdirSync(tools).find((name) => name.startsWith('jdk-'))
  return dir ? join(tools, dir) : ''
}

const javaHome = resolveJavaHome()
const childEnv = { ...process.env }
if (javaHome) {
  // sidecar 内的 Gradle 构建需要 JDK（jlink 运行时不含 javac）
  childEnv.JAVA_HOME = javaHome
}

function fail(message) {
  console.error(`\n[smoke] FAIL: ${message}`)
  process.exitCode = 1
}

if (!existsSync(java) || !existsSync(jar)) {
  fail(`后端产物缺失，请先执行 npm run backend:build（${backend}）`)
  process.exit(1)
}

const child = spawn(
  java,
  [
    `-Djava.io.tmpdir=${tmpDir}`,
    '-jar',
    jar,
    `--server.port=${port}`,
    `--app.token=${token}`,
    `--app.workspace=${workspace}`,
    ...(javaHome ? [`--app.java-home=${javaHome}`] : [])
  ],
  { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true, env: childEnv }
)
let stderr = ''
child.stderr.on('data', (chunk) => {
  stderr += chunk.toString()
})

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function waitHealthy(timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (child.exitCode !== null) return false
    try {
      const response = await fetch(`${base}/api/health`, { signal: AbortSignal.timeout(1500) })
      if (response.ok) return true
    } catch {
      // 尚未监听
    }
    await sleep(300)
  }
  return false
}

async function main() {
  if (!(await waitHealthy())) {
    fail(`健康检查超时\n${stderr.slice(-2000)}`)
    return
  }
  console.log('[smoke] health ok')

  const unauthorized = await fetch(`${base}/api/files`)
  if (unauthorized.status !== 401) fail(`未带 token 应为 401，实际 ${unauthorized.status}`)
  else console.log('[smoke] auth ok (401 without token)')

  const headers = { 'X-GMCH-Token': token }
  const traversal = await fetch(`${base}/api/file?path=..%2Foutside.txt`, { headers })
  if (traversal.status !== 400) fail(`路径越界应返回 400，实际 ${traversal.status}`)
  else console.log('[smoke] path traversal blocked')

  const files = await (await fetch(`${base}/api/files`, { headers })).json()
  if (!files.some((file) => file.path.endsWith('Main.java'))) fail('文件列表缺少 Main.java')
  else console.log(`[smoke] files ok (${files.length} entries)`)
  const wrapperJar = files.find((file) => file.path.endsWith('gradle-wrapper.jar'))
  if (!wrapperJar || wrapperJar.binary !== true) fail('文件列表未正确标记二进制产物')
  else console.log('[smoke] binary file metadata ok')

  const source = await (
    await fetch(`${base}/api/file?path=src%2Fmain%2Fjava%2FMain.java`, { headers })
  ).text()
  if (!source.includes('class Main')) fail('文件内容不正确')
  else console.log('[smoke] read file ok')

  // SSE：读取到 exit 事件为止
  const response = await fetch(`${base}/api/run?task=compileJava&token=${token}`, {
    signal: AbortSignal.timeout(180_000)
  })
  const text = await response.text()
  const exit = text.match(/event:exit\ndata:(-?\d+)/)
  if (!exit || exit[1] !== '0') {
    fail(`compileJava 退出码异常：${exit?.[1] ?? 'missing'}\n${text.slice(-1500)}`)
  } else {
    console.log('[smoke] compileJava ok (exit 0)')
  }

  const afterBuild = await (await fetch(`${base}/api/files`, { headers })).json()
  const compiled = afterBuild.find((file) => file.path === 'build/classes/java/main/Main.class')
  if (!compiled || compiled.binary !== true) fail('编译后未在文件树数据中找到 Main.class')
  else console.log('[smoke] build artifacts visible')
}

try {
  await main()
} finally {
  child.kill()
  await sleep(300)
  if (child.exitCode === null) child.kill('SIGKILL')
}

if (process.exitCode === 1) {
  console.error('[smoke] 失败')
} else {
  console.log('[smoke] 全部通过')
}
