<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import type { WorkspaceInfo } from '../../../shared/types'

const props = defineProps<{
  workspace: WorkspaceInfo | null
  recentWorkspaces: WorkspaceInfo[]
}>()

const emit = defineEmits<{
  switchWorkspace: [path: string]
  pickWorkspace: []
  revealWorkspace: [path: string]
  newProject: []
}>()

const trigger = ref<HTMLButtonElement | null>(null)
const menu = ref<HTMLElement | null>(null)
const open = ref(false)
const menuStyle = ref<Record<string, string>>({})

const recentProjects = computed(() =>
  props.recentWorkspaces.filter((item) => item.path !== props.workspace?.path).slice(0, 6)
)

const triggerLabel = computed(() =>
  props.workspace
    ? `Java 开发工作台，当前项目 ${props.workspace.name}`
    : 'Java 开发工作台，打开项目'
)

function updateMenuPosition(): void {
  if (!trigger.value) return
  const rect = trigger.value.getBoundingClientRect()
  const width = Math.min(332, window.innerWidth - 24)
  const left = Math.max(12, Math.min(rect.left, window.innerWidth - width - 12))
  const top = rect.bottom + 6
  menuStyle.value = {
    top: `${top}px`,
    left: `${left}px`,
    width: `${width}px`,
    maxHeight: `${Math.max(180, window.innerHeight - top - 12)}px`
  }
}

function focusFirstItem(): void {
  menu.value?.querySelector<HTMLElement>('[role="menuitem"]')?.focus()
}

async function openMenu(): Promise<void> {
  open.value = true
  await nextTick()
  updateMenuPosition()
  focusFirstItem()
}

function closeMenu(restoreFocus = true): void {
  if (!open.value) return
  open.value = false
  if (restoreFocus) trigger.value?.focus()
}

function toggleMenu(): void {
  if (open.value) closeMenu()
  else void openMenu()
}

function selectWorkspace(path: string): void {
  closeMenu()
  emit('switchWorkspace', path)
}

function pickWorkspace(): void {
  closeMenu()
  emit('pickWorkspace')
}

function createProject(): void {
  closeMenu()
  emit('newProject')
}

function revealWorkspace(): void {
  if (!props.workspace) return
  closeMenu()
  emit('revealWorkspace', props.workspace.path)
}

function onMenuKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeMenu()
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const items = Array.from(menu.value?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])
  if (items.length === 0) return
  event.preventDefault()
  const current = items.indexOf(document.activeElement as HTMLElement)
  let next = current
  if (event.key === 'ArrowDown') next = current < 0 ? 0 : (current + 1) % items.length
  else if (event.key === 'ArrowUp') next = current <= 0 ? items.length - 1 : current - 1
  else if (event.key === 'Home') next = 0
  else next = items.length - 1
  items[next]?.focus()
}

function onDocumentPointerDown(event: PointerEvent): void {
  if (!open.value) return
  const target = event.target as Node
  if (!trigger.value?.contains(target) && !menu.value?.contains(target)) closeMenu(false)
}

function onWindowResize(): void {
  if (open.value) updateMenuPosition()
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocumentPointerDown)
  window.addEventListener('resize', onWindowResize)
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  window.removeEventListener('resize', onWindowResize)
})
</script>

<template>
  <div class="workspace-switcher">
    <button
      ref="trigger"
      type="button"
      class="workspace-trigger"
      :class="{ open }"
      :title="workspace ? `${workspace.path} · 点击切换项目` : '打开项目目录'"
      :aria-label="triggerLabel"
      aria-haspopup="menu"
      :aria-expanded="open"
      aria-controls="project-switcher-menu"
      @click="toggleMenu"
    >
      <span class="brand-mark" aria-hidden="true">G</span>
      <span class="workspace-copy">
        <span class="workspace-title workspace-title-full">Java 开发工作台</span>
        <span class="workspace-title workspace-title-compact">Java 工作台</span>
        <span class="workspace-project">
          <span class="workspace-name">{{ workspace?.name ?? '打开项目' }}</span>
          <span v-if="workspace" class="workspace-root-label">项目根目录</span>
        </span>
      </span>
      <span class="switch-caret" :class="{ open }" aria-hidden="true" />
    </button>

    <Teleport to="body">
      <div
        v-if="open"
        id="project-switcher-menu"
        ref="menu"
        class="workspace-menu"
        role="menu"
        aria-label="项目切换"
        :style="menuStyle"
        @keydown="onMenuKeydown"
      >
        <div class="menu-current">
          <span class="menu-current-label">当前项目</span>
          <strong>{{ workspace?.name ?? '未打开项目' }}</strong>
          <span class="menu-current-path">{{ workspace?.path ?? '选择一个项目目录' }}</span>
        </div>

        <div v-if="recentProjects.length > 0" class="menu-section">
          <div class="menu-title">最近项目</div>
          <button
            v-for="project in recentProjects"
            :key="project.path"
            type="button"
            class="recent-row"
            role="menuitem"
            :title="project.path"
            @click="selectWorkspace(project.path)"
          >
            <span class="project-dot" aria-hidden="true" />
            <span class="recent-copy">
              <span class="recent-name">{{ project.name }}</span>
              <span class="recent-path">{{ project.path }}</span>
            </span>
          </button>
        </div>

        <div class="menu-separator" />
        <button type="button" class="menu-action" role="menuitem" @click="createProject">
          <svg viewBox="0 0 18 18" aria-hidden="true">
            <path d="M9 3.5v11M3.5 9h11" />
          </svg>
          新建 Java 项目
        </button>
        <button type="button" class="menu-action" role="menuitem" @click="pickWorkspace">
          <svg viewBox="0 0 18 18" aria-hidden="true">
            <path d="M2.5 5.2h5l1.4 2h6.6v6.3h-13z" />
            <path d="M2.5 5.2V3.5h5l1.4 1.7" />
          </svg>
          打开其他文件夹…
        </button>
        <button
          v-if="workspace"
          type="button"
          class="menu-action"
          role="menuitem"
          @click="revealWorkspace"
        >
          <svg viewBox="0 0 18 18" aria-hidden="true">
            <path d="M7 4H3.5v10.5H14V11" />
            <path d="M10 3.5h4.5V8M14.3 3.7 8.5 9.5" />
          </svg>
          在资源管理器中显示
        </button>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.workspace-switcher {
  flex: 0 0 clamp(132px, 18vw, 244px);
  min-width: 0;
}

.workspace-trigger {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  height: 42px;
  min-width: 0;
  padding: 4px 9px 4px 5px;
  overflow: hidden;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--text);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 120ms ease,
    background-color 120ms ease;
}

.workspace-trigger:hover,
.workspace-trigger.open {
  border-color: var(--border);
  background: var(--panel-raised);
}

.workspace-trigger:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: 1px;
}

.brand-mark {
  position: relative;
  display: grid;
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  overflow: hidden;
  border: 1px solid var(--accent-border);
  border-radius: 8px 3px 8px 3px;
  background: linear-gradient(145deg, rgb(221 161 94 / 22%), rgb(221 161 94 / 5%));
  color: var(--accent-strong);
  font-family: var(--font-brand);
  font-size: 17px;
  font-weight: 700;
  line-height: 1;
  place-items: center;
}

.brand-mark::after {
  position: absolute;
  right: -5px;
  bottom: -5px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--accent);
  content: '';
  opacity: 0.55;
}

.workspace-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
  line-height: 1.1;
}

.workspace-title {
  overflow: hidden;
  color: var(--text-tertiary);
  font-size: 11px;
  letter-spacing: 0.055em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-title-compact {
  display: none;
}

.workspace-project {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.workspace-name {
  overflow: hidden;
  color: var(--text-strong);
  font-family: var(--font-brand);
  font-size: 14px;
  font-weight: 650;
  letter-spacing: 0.035em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-root-label {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 5px;
  color: var(--text-tertiary);
  font-size: 11px;
  letter-spacing: 0;
}

.workspace-root-label::before {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--ok);
  content: '';
}

.switch-caret {
  flex: 0 0 8px;
  width: 8px;
  height: 8px;
  border-right: 1.5px solid var(--text-secondary);
  border-bottom: 1.5px solid var(--text-secondary);
  transform: rotate(45deg) translate(-1px, -1px);
  transition: transform 140ms ease;
}

.switch-caret.open {
  transform: rotate(225deg) translate(-2px, -2px);
}

.workspace-menu {
  position: fixed;
  z-index: 80;
  overflow: auto;
  padding: 6px;
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  background: var(--panel-raised);
  box-shadow: var(--shadow-menu);
}

.menu-current {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px 9px 10px;
  border-bottom: 1px solid var(--border);
}

.menu-current-label,
.menu-title {
  color: var(--text-tertiary);
  font-size: 11px;
  letter-spacing: 0.045em;
}

.menu-current strong {
  overflow: hidden;
  color: var(--text-strong);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-current-path {
  overflow: hidden;
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-section {
  padding-top: 5px;
}

.menu-title {
  padding: 6px 8px 5px;
}

.recent-row,
.menu-action {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  min-width: 0;
  min-height: 36px;
  padding: 7px 8px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--text);
  font-family: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.recent-row:hover,
.recent-row:focus-visible,
.menu-action:hover,
.menu-action:focus-visible {
  border-color: var(--border);
  outline: none;
  background: var(--accent-soft);
}

.project-dot {
  flex: 0 0 6px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-tertiary);
}

.recent-row:hover .project-dot,
.recent-row:focus-visible .project-dot {
  background: var(--accent);
}

.recent-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}

.recent-name {
  overflow: hidden;
  color: var(--text-strong);
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-path {
  overflow: hidden;
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-separator {
  height: 1px;
  margin: 5px 3px;
  background: var(--border);
}

.menu-action svg {
  flex: 0 0 17px;
  width: 17px;
  height: 17px;
  fill: none;
  stroke: var(--text-secondary);
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.4;
}

.menu-action:hover svg,
.menu-action:focus-visible svg {
  stroke: var(--accent-strong);
}

@media (max-width: 1240px) {
  .workspace-switcher {
    flex-basis: 170px;
  }
}

@media (max-width: 1040px) {
  .workspace-switcher {
    flex-basis: 168px;
  }

  .workspace-title-full {
    display: none;
  }

  .workspace-title-compact {
    display: block;
  }

  .workspace-root-label {
    display: none;
  }
}

@media (max-width: 860px) {
  .workspace-switcher {
    flex-basis: 126px;
  }

  .workspace-trigger {
    gap: 7px;
    padding-right: 6px;
  }

  .workspace-title {
    display: none;
  }
}
</style>
