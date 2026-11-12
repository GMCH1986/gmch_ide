import type { FileEntry } from '../../../shared/types'

export interface FileTreeItem {
  name: string
  path: string
  kind: 'file' | 'directory'
  binary: boolean
  children: FileTreeItem[]
}

const BUILD_DIRS = new Set(['build', 'out', 'target', 'dist'])

/** 将后端返回的扁平文件列表构造成稳定的目录树。 */
export function buildFileTree(files: FileEntry[]): FileTreeItem[] {
  const root: FileTreeItem = {
    name: '',
    path: '',
    kind: 'directory',
    binary: false,
    children: []
  }

  for (const file of files) {
    const parts = file.path.split('/').filter(Boolean)
    let parent = root

    parts.forEach((part, index) => {
      const path = parts.slice(0, index + 1).join('/')
      const isFile = index === parts.length - 1
      let item = parent.children.find((child) => child.path === path)

      if (!item) {
        item = {
          name: part,
          path,
          kind: isFile ? 'file' : 'directory',
          binary: isFile ? file.binary : false,
          children: []
        }
        parent.children.push(item)
      }

      parent = item
    })
  }

  sortTree(root.children)
  return root.children
}

/** 目录优先、同类按名称排序，保证刷新后树结构稳定。 */
export function sortTree(items: FileTreeItem[]): void {
  items.sort((a, b) => {
    if (a.kind !== b.kind) return a.kind === 'directory' ? -1 : 1
    return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' })
  })
  items.forEach((item) => sortTree(item.children))
}

/** build/out/target/dist 下的内容在界面中标记为构建产物。 */
export function isBuildArtifact(path: string): boolean {
  return BUILD_DIRS.has(path.split('/')[0] ?? '')
}

/** 返回文件路径上的全部父目录，用于展开当前打开文件。 */
export function parentDirectories(path: string): string[] {
  const parts = path.split('/').filter(Boolean)
  return parts.slice(0, -1).map((_, index) => parts.slice(0, index + 1).join('/'))
}

export function fileBadge(name: string): string {
  const lower = name.toLowerCase()
  if (lower.endsWith('.java')) return 'J'
  if (lower.endsWith('.gradle') || lower.endsWith('.kts')) return 'G'
  if (lower.endsWith('.jar')) return 'A'
  if (lower.endsWith('.class')) return 'C'
  if (lower.endsWith('.html')) return 'H'
  if (lower.endsWith('.properties')) return 'P'
  if (lower.endsWith('.md')) return 'M'
  return '·'
}
