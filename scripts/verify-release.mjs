#!/usr/bin/env node
/** 发布前校验：产物是否存在、版本号是否一致、体积是否在预期区间。 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
const dist = join(root, 'dist')

if (!existsSync(dist)) {
  console.error('dist/ 不存在，请先执行对应平台的构建命令。')
  process.exit(1)
}

const files = readdirSync(dist)
  .filter((name) => !name.endsWith('.blockmap'))
  .filter((name) => !['builder-debug.yml', 'builder-effective-config.yaml', '.icon-ico', 'win-unpacked', 'mac', 'linux-unpacked'].includes(name))
  .filter((name) => statSync(join(dist, name)).isFile())
const expected = [
  { platform: 'Windows', pattern: /-setup\.exe$/ },
  { platform: 'macOS', pattern: /\.(dmg|zip)$/ },
  { platform: 'Linux', pattern: /\.(AppImage|deb)$/ }
]

let found = 0
for (const item of expected) {
  const matches = files.filter((name) => item.pattern.test(name))
  if (matches.length > 0) found += 1
  const detail = matches.length > 0 ? matches.join(', ') : '（本机未构建）'
  console.log(`${item.platform.padEnd(8)} ${detail}`)
}

const versionMismatch = files.filter((name) => !name.includes(pkg.version))
if (versionMismatch.length > 0) {
  console.warn(`\n注意：以下产物文件名不含当前版本 ${pkg.version}：\n  ${versionMismatch.join('\n  ')}`)
}

for (const name of files) {
  const size = (statSync(join(dist, name)).size / 1024 / 1024).toFixed(1)
  console.log(`  ${name}  ${size} MB`)
}

if (found === 0) {
  console.error('\n未发现任何可识别的安装包。')
  process.exit(1)
}

console.log(`\n版本 ${pkg.version} 校验完成。`)
