#!/usr/bin/env node
/**
 * 构建 Java sidecar：
 *   1. gradlew bootJar  → backend/build/libs/app.jar
 *   2. jlink 裁剪运行时 → build/backend/runtime
 *   3. 汇总到 build/backend/{app.jar,runtime}
 */
import { spawnSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, rmSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const backend = join(root, 'backend')
const output = join(root, 'build', 'backend')
const isWin = process.platform === 'win32'

const MODULES = [
  'java.base',
  'java.logging',
  'java.naming',
  'java.management',
  'java.instrument',
  'java.security.jgss',
  'java.sql',
  'java.desktop',
  'java.xml',
  'jdk.unsupported',
  'jdk.crypto.ec'
].join(',')

function fail(message) {
  console.error(`\n[backend:build] ${message}`)
  process.exit(1)
}

function run(label, command, args, cwd) {
  console.log(`\n[backend:build] ${label}`)
  const result = isWin
    ? spawnSync('cmd.exe', ['/c', command, ...args], { cwd, stdio: 'inherit' })
    : spawnSync(command, args, { cwd, stdio: 'inherit' })
  if (result.status !== 0) {
    fail(`${label} 失败（exit=${result.status}）`)
  }
}

const javaHome = process.env.JAVA_HOME
if (!javaHome) fail('请设置 JAVA_HOME 指向 JDK 21')
const jlink = join(javaHome, 'bin', isWin ? 'jlink.exe' : 'jlink')
if (!existsSync(jlink)) fail(`未找到 jlink：${jlink}`)

const wrapper = join(backend, isWin ? 'gradlew.bat' : 'gradlew')
if (!existsSync(wrapper)) {
  fail('缺少 backend Gradle Wrapper，请先执行：cd backend && gradle wrapper --gradle-version 8.12')
}

run('构建 Spring Boot jar', wrapper, ['bootJar', '--console=plain'], backend)

const jar = join(backend, 'build', 'libs', 'app.jar')
if (!existsSync(jar)) fail(`未找到产物：${jar}`)

rmSync(output, { recursive: true, force: true })
mkdirSync(output, { recursive: true })
cpSync(jar, join(output, 'app.jar'))

run('裁剪运行时（jlink）', jlink, [
  '--add-modules',
  MODULES,
  '--strip-debug',
  '--no-header-files',
  '--no-man-pages',
  '--compress=zip-6',
  '--output',
  join(output, 'runtime')
])

const size = (path) => (statSync(path).size / 1024 / 1024).toFixed(1)
console.log(`\n[backend:build] 完成：app.jar ${size(join(output, 'app.jar'))} MB`)
console.log(`[backend:build] 运行时：${join(output, 'runtime')}`)
