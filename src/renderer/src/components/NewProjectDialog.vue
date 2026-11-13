<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useIdeStore } from '../stores/ide'

const ide = useIdeStore()

const name = ref('hello-java')
const parentDir = ref('')
const gradleVersion = ref('8.12')
const versions = ref<string[]>(['8.12', '8.5'])
const error = ref('')
const busy = ref(false)
const dialog = ref<HTMLElement | null>(null)
const firstInput = ref<HTMLInputElement | null>(null)
let previousFocus: HTMLElement | null = null

onMounted(async () => {
  previousFocus = document.activeElement as HTMLElement | null
  await nextTick()
  firstInput.value?.focus()
  const defaults = await ide.loadProjectDefaults()
  if (!defaults) return
  parentDir.value = defaults.parentDir
  name.value = defaults.name
  versions.value = defaults.gradleVersions
  gradleVersion.value = defaults.gradleVersions[0] ?? '8.12'
})

onUnmounted(() => {
  void nextTick(() => previousFocus?.focus?.())
})

async function browse(): Promise<void> {
  const dir = await window.api.pickDirectory()
  if (dir) parentDir.value = dir
}

async function submit(): Promise<void> {
  if (busy.value) return
  error.value = ''

  if (!name.value.trim()) {
    error.value = '请输入项目名称'
    firstInput.value?.focus()
    return
  }
  if (!parentDir.value.trim()) {
    error.value = '请选择项目存放目录'
    return
  }

  busy.value = true
  const result = await ide.createProject({
    parentDir: parentDir.value.trim(),
    name: name.value.trim(),
    gradleVersion: gradleVersion.value
  })
  busy.value = false
  if (result) error.value = result
}

function close(): void {
  if (!busy.value) ide.closeNewProject()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const focusable = Array.from(
    dialog.value.querySelectorAll<HTMLElement>(
      'button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex]:not([tabindex="-1"])'
    )
  )
  if (focusable.length === 0) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
</script>

<template>
  <div class="overlay" @pointerdown.self="close">
    <section
      ref="dialog"
      class="dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="new-project-title"
      aria-describedby="new-project-description"
      :aria-busy="busy"
      @keydown="onKeydown"
    >
      <div class="dialog-head">
        <span class="dialog-mark" aria-hidden="true">J</span>
        <div class="dialog-copy">
          <h2 id="new-project-title">新建 Java 项目</h2>
          <p id="new-project-description">创建一个可直接编译运行的 Gradle 项目。</p>
        </div>
      </div>

      <label class="field">
        <span>项目名称</span>
        <input
          ref="firstInput"
          v-model="name"
          type="text"
          spellcheck="false"
          placeholder="hello-java"
          :disabled="busy"
        />
      </label>

      <label class="field">
        <span>存放目录</span>
        <div class="row">
          <input v-model="parentDir" type="text" spellcheck="false" :disabled="busy" />
          <button class="tb-btn" type="button" :disabled="busy" @click="browse">浏览…</button>
        </div>
      </label>

      <label class="field">
        <span>Gradle 版本</span>
        <select v-model="gradleVersion" :disabled="busy">
          <option v-for="item in versions" :key="item" :value="item">{{ item }}</option>
        </select>
      </label>

      <p class="hint">
        将生成 settings.gradle、build.gradle、src/main/java/Main.java 与 Gradle Wrapper。
      </p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="busy" class="progress" role="status">正在生成项目文件与 Gradle Wrapper…</p>

      <div class="actions">
        <button class="tb-btn" type="button" :disabled="busy" @click="close">取消</button>
        <button class="tb-btn primary" type="submit" :disabled="busy" @click="submit">
          {{ busy ? '创建中…' : '创建项目' }}
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: var(--overlay-strong);
  backdrop-filter: blur(4px);
}

.dialog {
  width: 540px;
  max-width: calc(100vw - 40px);
  max-height: calc(100vh - 40px);
  overflow: auto;
  padding: 22px;
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  background: var(--panel-raised);
  box-shadow: var(--shadow-dialog);
}

.dialog-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.dialog-mark {
  display: grid;
  flex: 0 0 36px;
  width: 36px;
  height: 36px;
  border: 1px solid var(--accent-border);
  border-radius: 10px 3px 10px 3px;
  background: var(--accent-soft);
  color: var(--accent-strong);
  font-family: var(--font-brand);
  font-size: 18px;
  font-weight: 650;
  place-items: center;
}

.dialog-copy {
  min-width: 0;
}

h2 {
  margin: 0;
  color: var(--text-strong);
  font-family: var(--font-brand);
  font-size: 18px;
  font-weight: 550;
  letter-spacing: 0.02em;
}

.dialog-copy p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.field {
  display: block;
  margin-bottom: 14px;
}

.field > span {
  display: block;
  margin-bottom: 6px;
  color: var(--text-secondary);
  font-size: 12px;
}

input,
select {
  width: 100%;
  height: 34px;
  padding: 0 9px;
  border: 1px solid var(--border);
  border-radius: 5px;
  background: var(--editor-bg);
  color: var(--text);
  font-family: inherit;
  font-size: 13px;
}

input::placeholder {
  color: var(--text-tertiary);
  opacity: 1;
}

input:hover:not(:disabled),
select:hover:not(:disabled) {
  border-color: var(--border-strong);
}

input:focus,
select:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-soft);
}

input:disabled,
select:disabled {
  opacity: 0.66;
  cursor: wait;
}

.row {
  display: flex;
  gap: 8px;
}

.row input {
  flex: 1;
  min-width: 0;
}

.row .tb-btn {
  flex: 0 0 auto;
  height: 34px;
  border-color: var(--border);
  background: var(--panel);
}

.hint {
  margin: 6px 0 0;
  padding: 9px 10px;
  border: 1px solid var(--ok-border);
  border-radius: 6px;
  background: var(--ok-soft);
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.error {
  margin: 10px 0 0;
  color: var(--err);
  font-size: 12px;
  line-height: 1.5;
}

.progress {
  margin: 9px 0 0;
  color: var(--accent-strong);
  font-size: 12px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
}

.actions .tb-btn {
  height: 32px;
  border-color: var(--border);
  background: var(--panel-soft);
}

.actions .tb-btn:hover:not(:disabled) {
  border-color: var(--border-strong);
}

.primary:not(:disabled) {
  border-color: var(--accent-strong);
  background: var(--accent);
  color: var(--accent-ink);
  font-weight: 650;
}

.primary:hover:not(:disabled) {
  border-color: var(--accent-strong);
  background: var(--accent-strong);
}
</style>
