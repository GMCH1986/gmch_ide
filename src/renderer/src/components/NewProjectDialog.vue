<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useIdeStore } from '../stores/ide'

const ide = useIdeStore()

const name = ref('hello-java')
const parentDir = ref('')
const gradleVersion = ref('8.12')
const versions = ref<string[]>(['8.12', '8.5'])
const error = ref('')
const busy = ref(false)

onMounted(async () => {
  const defaults = await ide.loadProjectDefaults()
  if (!defaults) return
  parentDir.value = defaults.parentDir
  name.value = defaults.name
  versions.value = defaults.gradleVersions
  gradleVersion.value = defaults.gradleVersions[0] ?? '8.12'
})

async function browse(): Promise<void> {
  const dir = await window.api.pickDirectory()
  if (dir) parentDir.value = dir
}

async function submit(): Promise<void> {
  if (busy.value) return
  error.value = ''

  if (!name.value.trim()) {
    error.value = '请输入工程名'
    return
  }
  if (!parentDir.value.trim()) {
    error.value = '请选择工程存放目录'
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
</script>

<template>
  <div class="overlay" @click.self="close">
    <div class="dialog">
      <h3>新建 Java 工程</h3>

      <label class="field">
        <span>工程名</span>
        <input v-model="name" type="text" spellcheck="false" placeholder="hello-java" />
      </label>

      <label class="field">
        <span>存放目录</span>
        <div class="row">
          <input v-model="parentDir" type="text" spellcheck="false" />
          <button class="tb-btn" type="button" @click="browse">浏览…</button>
        </div>
      </label>

      <label class="field">
        <span>Gradle 版本</span>
        <select v-model="gradleVersion">
          <option v-for="item in versions" :key="item" :value="item">{{ item }}</option>
        </select>
      </label>

      <p class="hint">
        将生成 settings.gradle、build.gradle、src/main/java/Main.java 与 Gradle Wrapper。
      </p>
      <p v-if="error" class="error">{{ error }}</p>

      <div class="actions">
        <button class="tb-btn" type="button" :disabled="busy" @click="close">取消</button>
        <button class="tb-btn primary" type="button" :disabled="busy" @click="submit">
          {{ busy ? '创建中…' : '创建' }}
        </button>
      </div>
    </div>
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
  background: rgba(0, 0, 0, 0.45);
}
.dialog {
  width: 520px;
  max-width: calc(100vw - 40px);
  padding: 20px 22px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-soft);
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.45);
}
h3 {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 600;
}
.field {
  display: block;
  margin-bottom: 12px;
}
.field span {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--muted);
}
input,
select {
  width: 100%;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: 5px;
  background: var(--bg);
  color: var(--text);
  font-family: inherit;
  font-size: 12px;
}
input:focus,
select:focus {
  outline: none;
  border-color: var(--accent);
}
.row {
  display: flex;
  gap: 8px;
}
.row input {
  flex: 1;
}
.hint {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--muted);
}
.error {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--err);
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 18px;
}
.primary {
  border-color: rgba(108, 155, 255, 0.5);
  background: rgba(108, 155, 255, 0.16);
}
</style>
