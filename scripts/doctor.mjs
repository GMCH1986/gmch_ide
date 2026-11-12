#!/usr/bin/env node
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const isWin = process.platform === 'win32'
const results = []

function check(name, ok, detail) {
  results.push({ name, ok, detail })
}

function javaMajor() {
  const javaHome = process.env.JAVA_HOME
  const bin = javaHome
    ? join(javaHome, 'bin', isWin ? 'java.exe' : 'java')
    : 'java'
  const result = spawnSync(bin, ['-version'], { encoding: 'utf8' })
  const text = `${result.stdout ?? ''}${result.stderr ?? ''}`
  const match = text.match(/version "(\d+)(?:\.(\d+))?/)
  if (!match) return { major: 0, text: text.trim() || '未找到 java' }
  const major = match[1] === '1' ? Number(match[2]) : Number(match[1])
  return { major, text: `Java ${major}` }
}

const nodeMajor = Number(process.versions.node.split('.')[0])
check('Node.js ≥ 22', nodeMajor >= 22, process.version)

const java = javaMajor()
check('JDK ≥ 21（构建 sidecar 需要）', java.major >= 21, java.text)

const javaHome = process.env.JAVA_HOME
check('JAVA_HOME', Boolean(javaHome) && existsSync(javaHome), javaHome || '(未设置)')

const wrapper = join(root, 'backend', isWin ? 'gradlew.bat' : 'gradlew')
check(
  'backend Gradle Wrapper',
  existsSync(wrapper),
  existsSync(wrapper) ? wrapper : '缺失：cd backend && gradle wrapper --gradle-version 8.12'
)

const jlink = javaHome ? join(javaHome, 'bin', isWin ? 'jlink.exe' : 'jlink') : ''
check('jlink（打包运行时）', Boolean(jlink) && existsSync(jlink), jlink || '(JAVA_HOME 未设置)')

const width = Math.max(...results.map((item) => item.name.length))
for (const item of results) {
  const mark = item.ok ? 'OK  ' : 'FAIL'
  console.log(`${mark}  ${item.name.padEnd(width)}  ${item.detail}`)
}

const failed = results.filter((item) => !item.ok).length
if (failed > 0) {
  console.log(`\n${failed} 项未通过，请先修复环境再执行构建。`)
  process.exit(1)
}
console.log('\n环境检查通过。')
