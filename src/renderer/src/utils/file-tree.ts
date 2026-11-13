import type { FileEntry } from '../../../shared/types'

export interface FileTreeItem {
  name: string
  path: string
  kind: 'file' | 'directory'
  binary: boolean
  children: FileTreeItem[]
}

export interface VisibleFileTreeItem {
  item: FileTreeItem
  depth: number
  parentPath: string | null
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
  const itemsByPath = new Map<string, FileTreeItem>()

  for (const file of files) {
    const parts = file.path.split('/').filter(Boolean)
    let parent = root
    let currentPath = ''

    parts.forEach((part, index) => {
      currentPath = currentPath ? `${currentPath}/${part}` : part
      const isFile = index === parts.length - 1
      let item = itemsByPath.get(currentPath)

      if (!item) {
        item = {
          name: part,
          path: currentPath,
          kind: isFile ? 'file' : 'directory',
          binary: isFile ? file.binary : false,
          children: []
        }
        itemsByPath.set(currentPath, item)
        parent.children.push(item)
      }

      parent = item
    })
  }

  sortTree(root.children)
  return root.children
}

/** 将文件条目转换为可用于搜索结果展示的叶子节点。 */
export function fileEntryToTreeItem(file: FileEntry): FileTreeItem {
  return {
    name: file.name,
    path: file.path,
    kind: 'file',
    binary: file.binary,
    children: []
  }
}

/** 仅遍历已展开目录，避免大型项目一次性渲染整棵树。 */
export function visibleFileTree(
  items: FileTreeItem[],
  expanded: ReadonlySet<string>,
  depth = 0,
  parentPath: string | null = null,
  result: VisibleFileTreeItem[] = []
): VisibleFileTreeItem[] {
  for (const item of items) {
    result.push({ item, depth, parentPath })
    if (item.kind === 'directory' && expanded.has(item.path)) {
      visibleFileTree(item.children, expanded, depth + 1, item.path, result)
    }
  }
  return result
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
