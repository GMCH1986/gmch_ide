<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { BuildToolCommand, BuildToolModule, BuildToolProject } from '../../../shared/types'

const props = defineProps<{
  projects: BuildToolProject[]
  loading: boolean
  error: string
  activeCommandId: string | null
  running: boolean
  collapsed: boolean
  workspaceOpen: boolean
  workspaceName?: string | null
}>()

const emit = defineEmits<{
  run: [project: BuildToolProject, module: BuildToolModule, command: BuildToolCommand]
  refresh: []
  toggleCollapsed: []
}>()

const expandedProjects = ref<Set<string>>(new Set())
const expandedModules = ref<Set<string>>(new Set())
const initializedProjects = new Set<string>()
const initializedModules = new Set<string>()

/** HMR 或后端短暂重连时 props 可能尚未完成初始化，始终以空数组兜底。 */
const visibleProjects = computed<BuildToolProject[]>(() =>
  Array.isArray(props.projects) ? props.projects : []
)
const mavenCount = computed(
  () => visibleProjects.value.filter((project) => project.system === 'maven').length
)
const gradleCount = computed(
  () => visibleProjects.value.filter((project) => project.system === 'gradle').length
)

function addToSet(source: Set<string>, values: string[]): Set<string> {
  const next = new Set(source)
  for (const value of values) next.add(value)
  return next
}

function toggleProject(id: string): void {
  const next = new Set(expandedProjects.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedProjects.value = next
}

function toggleModule(id: string): void {
  const next = new Set(expandedModules.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedModules.value = next
}

function wrapperName(project: BuildToolProject): string {
  if (!project.wrapper) return '未检测到 Wrapper'
  return project.wrapper.split('/').pop() ?? project.wrapper
}

function commandAriaLabel(
  project: BuildToolProject,
  module: BuildToolModule,
  command: BuildToolCommand
): string {
  const system = project.system === 'maven' ? 'Maven' : 'Gradle'
  const moduleName = module.path || '项目根目录'
  return `${system} ${moduleName} ${command.label}：${command.description}`
}

function isCommandDisabled(command: BuildToolCommand): boolean {
  return !command.executable || (props.running && props.activeCommandId !== command.id)
}

watch(
  () => visibleProjects.value,
  (projects) => {
    const projectIds: string[] = []
    const rootModuleIds: string[] = []
    for (const project of projects) {
      if (!initializedProjects.has(project.id)) {
        initializedProjects.add(project.id)
        projectIds.push(project.id)
      }
      const rootModule = project.modules.find((module) => module.path === project.rootPath)
      if (rootModule && !initializedModules.has(rootModule.id)) {
        initializedModules.add(rootModule.id)
        rootModuleIds.push(rootModule.id)
      }
    }
    if (projectIds.length > 0) {
      expandedProjects.value = addToSet(expandedProjects.value, projectIds)
    }
    if (rootModuleIds.length > 0) {
      expandedModules.value = addToSet(expandedModules.value, rootModuleIds)
    }
  },
  { immediate: true }
)

watch(
  () => props.activeCommandId,
  (activeId) => {
    if (!activeId) return
    for (const project of visibleProjects.value) {
      for (const module of project.modules) {
        if (!module.commands.some((command) => command.id === activeId)) continue
        expandedProjects.value = addToSet(expandedProjects.value, [project.id])
        expandedModules.value = addToSet(expandedModules.value, [module.id])
        return
      }
    }
  }
)
</script>

<template>
  <aside
    class="build-tools"
    :class="{ collapsed, 'is-loading': loading }"
    aria-label="构建工具导航"
    :aria-busy="loading"
  >
    <div v-if="collapsed" class="collapsed-rail">
      <button
        type="button"
        class="rail-toggle"
        title="展开构建工具"
        aria-label="展开构建工具"
        @click="emit('toggleCollapsed')"
      >
        <svg viewBox="0 0 16 16" aria-hidden="true">
          <path d="m10 3-5 5 5 5" />
        </svg>
      </button>
      <span class="rail-label">构建</span>
      <div class="rail-projects" aria-label="已识别的构建工程">
        <button
          v-for="project in visibleProjects"
          :key="project.id"
          type="button"
          class="rail-project"
          :class="project.system"
          :title="`${project.system === 'maven' ? 'Maven' : 'Gradle'} · ${project.rootPath}`"
          :aria-label="`展开构建工具，查看 ${project.label}`"
          @click="emit('toggleCollapsed')"
        >
          {{ project.system === 'maven' ? 'M' : 'G' }}
        </button>
      </div>
      <span v-if="running" class="rail-running" title="构建任务运行中">
        <span class="spinner" aria-hidden="true" />
      </span>
    </div>

    <template v-else>
      <header class="panel-head">
        <div class="panel-heading">
          <h2>构建工具</h2>
          <p :title="workspaceName ?? undefined">{{ workspaceName || '未打开项目' }}</p>
        </div>
        <div class="head-actions">
          <button
            type="button"
            class="icon-button"
            :disabled="loading || !workspaceOpen"
            :title="workspaceOpen ? '重新识别 Maven / Gradle 工程' : '先打开项目'"
            aria-label="重新识别 Maven / Gradle 工程"
            @click="emit('refresh')"
          >
            <svg
              class="refresh-icon"
              :class="{ spinning: loading }"
              viewBox="0 0 16 16"
              aria-hidden="true"
            >
              <path d="M13.2 6.2A5.5 5.5 0 1 0 13 9.7" />
              <path d="M13.2 2.8v3.6H9.6" />
            </svg>
          </button>
          <button
            type="button"
            class="icon-button"
            title="收起构建工具"
            aria-label="收起构建工具"
            @click="emit('toggleCollapsed')"
          >
            <svg viewBox="0 0 16 16" aria-hidden="true">
              <path d="m6 3 5 5-5 5" />
            </svg>
          </button>
        </div>
      </header>

      <div v-if="visibleProjects.length > 0" class="tool-summary" aria-label="构建工程统计">
        <span v-if="mavenCount" class="summary-item maven">
          <span class="summary-mark">M</span>
          Maven {{ mavenCount }}
        </span>
        <span v-if="gradleCount" class="summary-item gradle">
          <span class="summary-mark">G</span>
          Gradle {{ gradleCount }}
        </span>
        <span v-if="running" class="summary-running">
          <span class="spinner" aria-hidden="true" />
          运行中
        </span>
      </div>

      <div class="panel-body">
        <div
          v-if="loading && visibleProjects.length === 0"
          class="state-panel loading-state"
          role="status"
        >
          <span class="state-spinner" aria-hidden="true" />
          <strong>正在分析构建文件</strong>
          <p>识别 Maven、Gradle 工程与可执行任务…</p>
        </div>

        <div v-else-if="error" class="state-panel error-state" role="alert">
          <span class="state-icon" aria-hidden="true">
            <svg viewBox="0 0 18 18">
              <path d="M9 2.5 16 15H2L9 2.5Z" />
              <path d="M9 6.5v3.8M9 13v.1" />
            </svg>
          </span>
          <strong>构建工具识别失败</strong>
          <p>{{ error }}</p>
          <button type="button" class="retry-button" @click="emit('refresh')">重新识别</button>
        </div>

        <div v-else-if="!workspaceOpen" class="state-panel empty-state">
          <span class="state-icon" aria-hidden="true">
            <svg viewBox="0 0 18 18">
              <path d="M2.5 5.2h4l1.4 1.7h7.6v7.4h-13V5.2Z" />
              <path d="M2.5 8h13" />
            </svg>
          </span>
          <strong>等待打开项目</strong>
          <p>打开 Maven 或 Gradle 项目后，这里会显示模块和可执行任务。</p>
        </div>

        <div v-else-if="visibleProjects.length === 0" class="state-panel empty-state">
          <span class="state-icon" aria-hidden="true">
            <svg viewBox="0 0 18 18">
              <path d="M3 4.5h5l1.4 1.7H15v8.3H3V4.5Z" />
              <path d="m6.5 10 1.8 1.8 3.4-3.4" />
            </svg>
          </span>
          <strong>未发现构建工程</strong>
          <p>当前项目没有检测到 Maven 或 Gradle 构建文件。点击刷新可重新扫描。</p>
          <button type="button" class="retry-button" @click="emit('refresh')">重新扫描</button>
        </div>

        <div v-else class="project-tree">
          <section
            v-for="project in visibleProjects"
            :key="project.id"
            class="project"
            :class="project.system"
          >
            <button
              type="button"
              class="project-row"
              :aria-expanded="expandedProjects.has(project.id)"
              @click="toggleProject(project.id)"
            >
              <svg
                class="chevron"
                :class="{ open: expandedProjects.has(project.id) }"
                viewBox="0 0 14 14"
                aria-hidden="true"
              >
                <path d="m5 3 4 4-4 4" />
              </svg>
              <span class="system-badge" aria-hidden="true">
                {{ project.system === 'maven' ? 'M' : 'G' }}
              </span>
              <span class="project-copy">
                <span class="project-name">{{ project.label }}</span>
                <span class="project-path" :title="project.rootPath">{{ project.rootPath }}</span>
              </span>
              <span
                class="wrapper-state"
                :class="{ missing: !project.wrapperAvailable }"
                :title="project.wrapper ?? '未找到构建 Wrapper'"
              >
                {{ project.wrapperAvailable ? wrapperName(project) : '缺少 Wrapper' }}
              </span>
            </button>

            <div v-if="expandedProjects.has(project.id)" class="modules">
              <div
                v-for="module in project.modules"
                :key="module.id"
                class="module"
                :class="{
                  active: module.commands.some((command) => command.id === activeCommandId)
                }"
              >
                <button
                  type="button"
                  class="module-row"
                  :aria-expanded="expandedModules.has(module.id)"
                  @click="toggleModule(module.id)"
                >
                  <span class="tree-guide" aria-hidden="true" />
                  <svg
                    class="chevron"
                    :class="{ open: expandedModules.has(module.id) }"
                    viewBox="0 0 14 14"
                    aria-hidden="true"
                  >
                    <path d="m5 3 4 4-4 4" />
                  </svg>
                  <svg class="module-icon" viewBox="0 0 16 16" aria-hidden="true">
                    <path d="M3 3.5h10v9H3z" />
                    <path d="M3 6.5h10M6 3.5v9" />
                  </svg>
                  <span class="module-copy">
                    <span class="module-name">{{ module.name }}</span>
                    <span class="module-path" :title="module.path || '项目根目录'">
                      {{ module.path || '项目根目录' }}
                    </span>
                  </span>
                  <span class="task-count">{{ module.commands.length }}</span>
                  <span class="task-path" :title="module.taskPath">{{ module.taskPath }}</span>
                </button>

                <div v-if="expandedModules.has(module.id)" class="commands">
                  <button
                    v-for="command in module.commands"
                    :key="command.id"
                    type="button"
                    class="command-row"
                    :class="{ active: activeCommandId === command.id }"
                    :disabled="isCommandDisabled(command)"
                    :title="
                      command.executable ? command.commandLine : (command.disabledReason ?? '')
                    "
                    :aria-label="commandAriaLabel(project, module, command)"
                    @click="emit('run', project, module, command)"
                  >
                    <span class="command-icon" aria-hidden="true">
                      <span v-if="activeCommandId === command.id" class="spinner" />
                      <svg v-else viewBox="0 0 16 16">
                        <path d="m5 4 6 4-6 4V4Z" />
                      </svg>
                    </span>
                    <span class="command-copy">
                      <span class="command-name">{{ command.label }}</span>
                      <span class="command-description">{{ command.description }}</span>
                    </span>
                    <span v-if="activeCommandId === command.id" class="command-status">运行中</span>
                    <span
                      v-else-if="!command.executable"
                      class="command-status warning"
                      :title="command.disabledReason ?? undefined"
                    >
                      Wrapper 缺失
                    </span>
                  </button>
                </div>
              </div>
            </div>
          </section>
        </div>

        <div v-if="loading && visibleProjects.length > 0" class="loading-strip" role="status">
          <span class="spinner" aria-hidden="true" />
          正在重新扫描构建工具…
        </div>
      </div>
    </template>
  </aside>
</template>

<style scoped>
.build-tools {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--panel);
  box-shadow: inset 1px 0 0 var(--border);
}

.build-tools.collapsed {
  grid-template-rows: minmax(0, 1fr);
  box-shadow: inset 1px 0 0 var(--border);
}

.collapsed-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  min-height: 0;
  padding: 10px 0;
}

.rail-toggle,
.icon-button {
  display: inline-grid;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  place-items: center;
  transition:
    border-color 120ms ease,
    background-color 120ms ease,
    color 120ms ease;
}

.rail-toggle:hover,
.icon-button:hover:not(:disabled) {
  border-color: var(--border-strong);
  background: var(--panel-raised);
  color: var(--text-strong);
}

.icon-button:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}

.rail-toggle svg,
.icon-button svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.6;
}

.rail-label {
  color: var(--text-tertiary);
  font-size: 10px;
  letter-spacing: 0.14em;
  writing-mode: vertical-rl;
}

.rail-projects {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin-top: 2px;
  overflow: auto;
}

.rail-project {
  display: grid;
  width: 25px;
  height: 25px;
  padding: 0;
  border: 1px solid var(--border-strong);
  border-radius: 5px;
  background: var(--panel-soft);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  place-items: center;
}

.rail-project.maven {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.rail-project.gradle {
  border-color: var(--teal-border);
  background: var(--teal-soft);
  color: var(--teal);
}

.rail-project:hover {
  filter: brightness(1.18);
}

.rail-running {
  display: grid;
  margin-top: auto;
  width: 25px;
  height: 25px;
  border-radius: 5px;
  background: var(--accent-soft);
  place-items: center;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 52px;
  padding: 8px 8px 8px 12px;
  border-bottom: 1px solid var(--border);
  box-shadow: var(--inset-highlight);
}

.panel-heading {
  min-width: 0;
}

.panel-heading h2 {
  margin: 0;
  color: var(--text-strong);
  font-family: var(--font-brand);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.025em;
}

.panel-heading p {
  max-width: 190px;
  margin: 2px 0 0;
  overflow: hidden;
  color: var(--text-tertiary);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 1px;
  margin-left: auto;
}

.tool-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 35px;
  padding: 5px 10px;
  overflow: hidden;
  border-bottom: 1px solid var(--border);
  background: var(--panel-soft);
  color: var(--text-tertiary);
  font-size: 10.5px;
  white-space: nowrap;
}

.summary-item,
.summary-running {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.summary-mark {
  display: inline-grid;
  width: 16px;
  height: 16px;
  border: 1px solid currentColor;
  border-radius: 3px;
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
  place-items: center;
}

.summary-item.maven {
  color: var(--accent-strong);
}

.summary-item.gradle {
  color: var(--teal);
}

.summary-running {
  margin-left: auto;
  color: var(--accent-strong);
}

.panel-body {
  position: relative;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
}

.project-tree {
  padding: 7px 6px 14px;
}

.project + .project {
  margin-top: 7px;
  padding-top: 7px;
  border-top: 1px solid var(--border);
}

.project-row,
.module-row,
.command-row {
  width: 100%;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
}

.project-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 44px;
  padding: 5px 7px;
  border-radius: 6px;
  cursor: pointer;
}

.project-row:hover,
.module-row:hover {
  background: var(--surface-wash);
}

.project-row:focus-visible,
.module-row:focus-visible,
.command-row:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: -2px;
}

.chevron {
  flex: 0 0 12px;
  width: 12px;
  height: 12px;
  fill: none;
  stroke: var(--text-tertiary);
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.5;
  transition: transform 140ms ease;
}

.chevron.open {
  transform: rotate(90deg);
}

.system-badge {
  display: grid;
  flex: 0 0 24px;
  width: 24px;
  height: 24px;
  border: 1px solid currentColor;
  border-radius: 5px;
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 700;
  place-items: center;
}

.project.maven .system-badge {
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.project.gradle .system-badge {
  background: var(--teal-soft);
  color: var(--teal);
}

.project-copy,
.module-copy,
.command-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  gap: 1px;
}

.project-name {
  overflow: hidden;
  color: var(--text-strong);
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-path,
.module-path {
  overflow: hidden;
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wrapper-state {
  flex: 0 0 auto;
  max-width: 72px;
  overflow: hidden;
  padding: 2px 5px;
  border: 1px solid var(--border);
  border-radius: 3px;
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wrapper-state.missing {
  border-color: var(--err-border);
  background: var(--err-soft);
  color: var(--err);
}

.modules {
  padding: 1px 0 2px 10px;
}

.module {
  position: relative;
  padding-left: 7px;
}

.tree-guide {
  position: absolute;
  top: -2px;
  bottom: -2px;
  left: 0;
  width: 1px;
  background: var(--border);
}

.module-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 5px;
  min-height: 36px;
  padding: 4px 6px 4px 7px;
  border-radius: 5px;
  cursor: pointer;
}

.module-icon {
  flex: 0 0 14px;
  width: 14px;
  height: 14px;
  fill: none;
  stroke: var(--text-tertiary);
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.2;
}

.module-name {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 11.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-count {
  display: grid;
  flex: 0 0 18px;
  min-width: 18px;
  height: 17px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--panel-raised);
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 9px;
  place-items: center;
}

.task-path {
  flex: 0 0 auto;
  max-width: 60px;
  overflow: hidden;
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.module.active > .module-row {
  background: var(--accent-soft);
}

.commands {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin: 0 0 4px 25px;
  padding: 2px 0 2px 8px;
  border-left: 1px solid var(--border);
}

.command-row {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 38px;
  padding: 4px 7px;
  border: 1px solid transparent;
  border-radius: 5px;
  cursor: pointer;
  transition:
    border-color 120ms ease,
    background-color 120ms ease;
}

.command-row:hover:not(:disabled) {
  border-color: var(--border);
  background: var(--panel-raised);
}

.command-row:disabled {
  cursor: not-allowed;
}

.command-row.active {
  border-color: var(--accent-border);
  background: var(--accent-soft);
}

.command-row:disabled:not(.active) .command-copy {
  opacity: 0.5;
}

.command-icon {
  display: grid;
  flex: 0 0 21px;
  width: 21px;
  height: 21px;
  border: 1px solid var(--border);
  border-radius: 4px;
  background: var(--panel-soft);
  color: var(--text-tertiary);
  place-items: center;
}

.command-icon svg {
  width: 13px;
  height: 13px;
  fill: none;
  stroke: currentColor;
  stroke-linejoin: round;
  stroke-width: 1.4;
}

.command-row:hover:not(:disabled) .command-icon {
  border-color: var(--accent-border);
  color: var(--accent-strong);
}

.command-name {
  color: var(--text);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 600;
}

.command-description {
  overflow: hidden;
  color: var(--text-tertiary);
  font-size: 9.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.command-status {
  flex: 0 0 auto;
  color: var(--accent-strong);
  font-size: 9px;
}

.command-status.warning {
  color: var(--err);
}

.spinner {
  display: inline-block;
  width: 11px;
  height: 11px;
  border: 1.5px solid var(--accent-border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 700ms linear infinite;
}

.icon-button svg.spinning {
  animation: spin 800ms linear infinite;
}

.state-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 230px;
  padding: 28px 20px;
  color: var(--text-secondary);
  text-align: center;
}

.state-panel strong {
  margin-top: 11px;
  color: var(--text);
  font-size: 12px;
  font-weight: 650;
}

.state-panel p {
  max-width: 230px;
  margin: 6px 0 0;
  color: var(--text-tertiary);
  font-size: 11px;
  line-height: 1.65;
}

.state-icon {
  display: grid;
  width: 36px;
  height: 36px;
  border: 1px solid var(--border-strong);
  border-radius: 9px 3px 9px 3px;
  background: var(--panel-soft);
  color: var(--text-tertiary);
  place-items: center;
}

.state-icon svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.25;
}

.error-state .state-icon {
  border-color: var(--err-border);
  background: var(--err-soft);
  color: var(--err);
}

.state-spinner {
  width: 22px;
  height: 22px;
  margin-bottom: 2px;
  border: 2px solid var(--accent-border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 760ms linear infinite;
}

.retry-button {
  margin-top: 13px;
  padding: 5px 10px;
  border: 1px solid var(--border-strong);
  border-radius: 4px;
  background: var(--panel-raised);
  color: var(--text-secondary);
  font: inherit;
  font-size: 11px;
  cursor: pointer;
}

.retry-button:hover {
  border-color: var(--accent-border);
  color: var(--accent-strong);
}

.loading-strip {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 32px;
  border-top: 1px solid var(--accent-border);
  background: color-mix(in srgb, var(--panel) 92%, transparent);
  color: var(--accent-strong);
  font-size: 10.5px;
  backdrop-filter: blur(6px);
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1040px) {
  .wrapper-state,
  .task-path {
    display: none;
  }
}
</style>
