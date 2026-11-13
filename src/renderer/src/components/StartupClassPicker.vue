<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { StartupClassInfo } from '../../../shared/types'

const props = defineProps<{
  workspaceOpen: boolean
  startupClass: StartupClassInfo | null
  startupClasses: StartupClassInfo[]
  startupScanning: boolean
  startupError: string
}>()

const emit = defineEmits<{
  'open-startup-class': [path: string]
  'rescan-startup': []
}>()

const pickerRoot = ref<HTMLElement | null>(null)
const isOpen = ref(false)

const triggerLabel = computed(() => {
  if (!props.workspaceOpen) return '未打开项目'
  if (props.startupClass) return props.startupClass.className
  if (props.startupScanning) return '识别中'
  if (props.startupError) return '识别失败'
  return '未识别'
})

const triggerTitle = computed(() => {
  if (!props.workspaceOpen) return '打开项目后自动识别启动类'
  if (props.startupClass) {
    const fqcn = props.startupClass.packageName
      ? `${props.startupClass.packageName}.${props.startupClass.className}`
      : props.startupClass.className
    return `${fqcn}\n${props.startupClass.path}\n点击查看全部候选`
  }
  if (props.startupScanning) return '正在扫描 Java 源码中的启动类…'
  if (props.startupError) return props.startupError
  return '未发现带 main 方法或 SpringBootApplication 注解的 Java 类'
})

const stateClass = computed(() => ({
  ready: Boolean(props.startupClass),
  scanning: props.startupScanning,
  error: Boolean(props.startupError) && !props.startupScanning,
  empty: !props.startupClass && !props.startupScanning && !props.startupError
}))

const buildLabel = (candidate: StartupClassInfo): string => {
  if (candidate.buildSystem === 'maven') return 'Maven'
  if (candidate.buildSystem === 'gradle') return 'Gradle'
  return ''
}

function toggle(): void {
  if (!props.workspaceOpen) return
  isOpen.value = !isOpen.value
}

function openCandidate(candidate: StartupClassInfo): void {
  emit('open-startup-class', candidate.path)
  isOpen.value = false
}

function rescan(): void {
  emit('rescan-startup')
}

function onPointerDown(event: PointerEvent): void {
  if (!isOpen.value) return
  const target = event.target
  if (target instanceof Node && !pickerRoot.value?.contains(target)) isOpen.value = false
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') isOpen.value = false
}

watch(
  () => props.workspaceOpen,
  (open) => {
    if (!open) isOpen.value = false
  }
)

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown)
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', onPointerDown)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div ref="pickerRoot" class="startup-picker">
    <button
      type="button"
      :class="['startup-trigger', stateClass]"
      :disabled="!workspaceOpen"
      :title="triggerTitle"
      aria-haspopup="menu"
      :aria-expanded="isOpen"
      @click="toggle"
    >
      <span class="startup-glyph" aria-hidden="true">
        <span class="startup-glyph-dot" />
      </span>
      <span class="startup-prefix">启动类</span>
      <span class="startup-name">{{ triggerLabel }}</span>
      <span v-if="startupClasses.length > 1" class="startup-count">
        {{ startupClasses.length }}
      </span>
      <span v-if="workspaceOpen" class="startup-chevron" aria-hidden="true" />
    </button>

    <div v-if="isOpen" class="startup-menu" role="menu" aria-label="Java 启动类候选">
      <div class="startup-menu-head">
        <div class="startup-menu-title">
          <span>自动识别启动类</span>
          <span class="startup-menu-meta">
            {{ startupScanning ? '扫描中…' : `${startupClasses.length} 个候选` }}
          </span>
        </div>
        <button type="button" class="startup-rescan" :disabled="startupScanning" @click="rescan">
          {{ startupScanning ? '识别中' : '重新识别' }}
        </button>
      </div>

      <div v-if="startupClasses.length > 0" class="startup-list">
        <button
          v-for="candidate in startupClasses"
          :key="candidate.path"
          type="button"
          role="menuitem"
          :class="['startup-candidate', { selected: startupClass?.path === candidate.path }]"
          :title="candidate.path"
          @click="openCandidate(candidate)"
        >
          <span class="candidate-mark" aria-hidden="true">
            {{ candidate.springBoot ? 'S' : 'm' }}
          </span>
          <span class="candidate-body">
            <span class="candidate-line">
              <span class="candidate-name">{{ candidate.className }}</span>
              <span v-if="candidate.springBoot" class="candidate-badge spring">Spring</span>
              <span v-else class="candidate-badge main">main</span>
              <span v-if="buildLabel(candidate)" class="candidate-badge build">
                {{ buildLabel(candidate) }}
              </span>
            </span>
            <span class="candidate-package">
              {{ candidate.packageName || '默认包' }}
            </span>
            <span class="candidate-path">{{ candidate.path }}</span>
          </span>
          <span v-if="startupClass?.path === candidate.path" class="candidate-current"> 当前 </span>
        </button>
      </div>

      <div v-else-if="startupScanning" class="startup-empty">
        <span class="startup-spinner" aria-hidden="true" />
        <span>正在扫描 Java 源码，无需构建或安装项目…</span>
      </div>
      <div v-else-if="startupError" class="startup-empty error" role="alert">
        {{ startupError }}
      </div>
      <div v-else class="startup-empty">
        未发现带 <code>main</code> 方法或 <code>@SpringBootApplication</code> 的 Java 类。
      </div>

      <div class="startup-menu-foot">点击候选项会打开源码；识别过程不会修改项目构建配置。</div>
    </div>
  </div>
</template>

<style scoped>
.startup-picker {
  position: relative;
  flex: 0 1 auto;
  min-width: 0;
}

.startup-trigger {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  height: 22px;
  padding: 0 7px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 5px;
  background: var(--surface-wash);
  color: var(--text-secondary);
  font-size: 11px;
  white-space: nowrap;
  cursor: pointer;
  transition:
    border-color 120ms ease,
    background 120ms ease,
    color 120ms ease;
}

.startup-trigger:hover:not(:disabled) {
  border-color: var(--control-border-hover);
  background: var(--hover-surface);
  color: var(--text);
}

.startup-trigger:disabled {
  cursor: default;
  opacity: 0.62;
}

.startup-trigger.ready {
  border-color: var(--ok-border);
  background: var(--ok-soft);
  color: var(--ok);
}

.startup-trigger.scanning {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.startup-trigger.error {
  border-color: var(--err-border);
  background: var(--err-soft);
  color: var(--err);
}

.startup-glyph {
  position: relative;
  display: inline-grid;
  flex: 0 0 12px;
  width: 12px;
  height: 12px;
  border: 1px solid currentColor;
  border-radius: 3px 1px 3px 1px;
  place-items: center;
}

.startup-glyph::before {
  position: absolute;
  top: 3px;
  width: 4px;
  height: 4px;
  border-top: 1px solid currentColor;
  border-right: 1px solid currentColor;
  content: '';
  transform: rotate(45deg);
}

.startup-glyph-dot {
  position: absolute;
  bottom: 2px;
  width: 2px;
  height: 2px;
  border-radius: 50%;
  background: currentColor;
}

.startup-prefix {
  color: var(--text-tertiary);
}

.startup-name {
  max-width: 158px;
  overflow: hidden;
  color: currentColor;
  font-family: var(--font-mono);
  font-weight: 600;
  text-overflow: ellipsis;
}

.startup-count {
  min-width: 15px;
  padding: 0 3px;
  border-radius: 999px;
  background: rgb(255 255 255 / 9%);
  color: inherit;
  font-family: var(--font-mono);
  font-size: 9px;
  line-height: 14px;
  text-align: center;
}

.startup-chevron {
  width: 4px;
  height: 4px;
  margin-left: 1px;
  border-right: 1px solid currentColor;
  border-bottom: 1px solid currentColor;
  opacity: 0.72;
  transform: translateY(-1px) rotate(45deg);
}

.startup-menu {
  position: absolute;
  right: 0;
  bottom: calc(100% + 7px);
  z-index: 80;
  width: min(438px, calc(100vw - 24px));
  overflow: hidden;
  border: 1px solid var(--border-strong);
  border-radius: 9px;
  background: var(--panel-raised);
  box-shadow: var(--shadow-menu);
  color: var(--text-secondary);
}

.startup-menu-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 11px 9px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.startup-menu-title {
  display: flex;
  align-items: baseline;
  gap: 7px;
  color: var(--text-strong);
  font-size: 12px;
  font-weight: 650;
}

.startup-menu-meta {
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 500;
}

.startup-rescan {
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--border-strong);
  border-radius: 5px;
  background: var(--panel-raised);
  color: var(--text-secondary);
  font-size: 11px;
  cursor: pointer;
}

.startup-rescan:hover:not(:disabled) {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.startup-rescan:disabled {
  cursor: wait;
  opacity: 0.7;
}

.startup-list {
  max-height: min(348px, 48vh);
  padding: 5px;
  overflow-y: auto;
}

.startup-candidate {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 9px;
  width: 100%;
  padding: 8px 8px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.startup-candidate:hover {
  border-color: var(--border);
  background: var(--hover-surface);
}

.startup-candidate.selected {
  border-color: var(--ok-border);
  background: var(--ok-soft);
}

.candidate-mark {
  display: grid;
  flex: 0 0 24px;
  width: 24px;
  height: 24px;
  margin-top: 1px;
  border: 1px solid var(--accent-border);
  border-radius: 6px 2px 6px 2px;
  background: var(--accent-soft);
  color: var(--accent-strong);
  font-family: var(--font-brand);
  font-size: 13px;
  font-weight: 700;
  place-items: center;
}

.startup-candidate.selected .candidate-mark {
  border-color: var(--ok-border);
  background: rgb(98 195 163 / 15%);
  color: var(--ok);
}

.candidate-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}

.candidate-line {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
}

.candidate-name {
  overflow: hidden;
  color: var(--text-strong);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.candidate-badge {
  flex: 0 0 auto;
  padding: 1px 4px;
  border: 1px solid var(--border);
  border-radius: 3px;
  color: var(--text-tertiary);
  font-size: 9px;
  line-height: 13px;
}

.candidate-badge.spring {
  border-color: var(--ok-border);
  background: var(--ok-soft);
  color: var(--ok);
}

.candidate-badge.main {
  border-color: var(--info-border);
  background: var(--info-soft);
  color: var(--info);
}

.candidate-package,
.candidate-path {
  overflow: hidden;
  color: var(--text-tertiary);
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.candidate-path {
  color: var(--dim);
  font-size: 9px;
}

.candidate-current {
  flex: 0 0 auto;
  align-self: center;
  padding: 2px 5px;
  border-radius: 3px;
  background: var(--ok-soft);
  color: var(--ok);
  font-size: 9px;
}

.startup-empty {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 58px;
  padding: 12px;
  color: var(--text-tertiary);
  font-size: 11px;
  line-height: 1.5;
}

.startup-empty.error {
  color: var(--err);
}

.startup-empty code {
  color: var(--text-secondary);
  font-family: var(--font-mono);
}

.startup-spinner {
  flex: 0 0 12px;
  width: 12px;
  height: 12px;
  border: 1.5px solid var(--accent-border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: startup-spin 700ms linear infinite;
}

.startup-menu-foot {
  padding: 7px 11px 8px;
  border-top: 1px solid var(--border);
  background: var(--panel-soft);
  color: var(--dim);
  font-size: 10px;
}

@keyframes startup-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1180px) {
  .startup-prefix {
    display: none;
  }

  .startup-name {
    max-width: 126px;
  }
}

@media (max-width: 980px) {
  .startup-name {
    max-width: 96px;
  }
}
</style>
