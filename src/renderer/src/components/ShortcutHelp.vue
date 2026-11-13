<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'

const emit = defineEmits<{
  close: []
}>()

const dialog = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
let previousFocus: HTMLElement | null = null

const groups = [
  {
    title: '项目与文件',
    shortcuts: [
      { label: '打开项目', keys: ['Ctrl', 'O'] },
      { label: '新建项目', keys: ['Ctrl', 'Shift', 'N'] },
      { label: '快速打开文件', keys: ['Ctrl', 'P'] },
      { label: '保存当前文件', keys: ['Ctrl', 'S'] }
    ]
  },
  {
    title: '构建与运行',
    shortcuts: [
      { label: '编译 Java', keys: ['F7'] },
      { label: '运行程序', keys: ['F5'] },
      { label: '停止运行', keys: ['Shift', 'F5'] }
    ]
  },
  {
    title: '项目树',
    shortcuts: [
      { label: '上下移动', keys: ['↑', '↓'] },
      { label: '展开或进入', keys: ['→'] },
      { label: '收起或返回', keys: ['←'] },
      { label: '打开文件或切换目录', keys: ['Enter'] },
      { label: '跳到首项或末项', keys: ['Home', 'End'] }
    ]
  },
  {
    title: '面板与弹窗',
    shortcuts: [
      { label: '打开快捷键帮助', keys: ['F1'] },
      { label: '切换日志页签', keys: ['←', '→'] },
      { label: '调整日志高度', keys: ['↑', '↓'] },
      { label: '关闭当前弹窗', keys: ['Esc'] }
    ]
  }
]

function close(): void {
  emit('close')
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
      'button:not(:disabled), [tabindex]:not([tabindex="-1"])'
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

onMounted(() => {
  previousFocus = document.activeElement as HTMLElement | null
  void nextTick(() => closeButton.value?.focus())
})

onUnmounted(() => {
  void nextTick(() => previousFocus?.focus?.())
})
</script>

<template>
  <div class="shortcut-overlay" @pointerdown.self="close">
    <section
      ref="dialog"
      class="shortcut-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="shortcut-title"
      aria-describedby="shortcut-description"
      @keydown="onKeydown"
    >
      <header class="shortcut-head">
        <div>
          <p class="eyebrow">键盘优先</p>
          <h2 id="shortcut-title">快捷键</h2>
          <p id="shortcut-description">无需离开编辑器即可完成常用操作。</p>
        </div>
        <button ref="closeButton" type="button" class="close-button" @click="close">
          关闭
          <kbd>Esc</kbd>
        </button>
      </header>

      <div class="shortcut-grid">
        <section v-for="group in groups" :key="group.title" class="shortcut-group">
          <h3>{{ group.title }}</h3>
          <dl>
            <div v-for="shortcut in group.shortcuts" :key="shortcut.label" class="shortcut-row">
              <dt>{{ shortcut.label }}</dt>
              <dd>
                <template v-for="(key, index) in shortcut.keys" :key="key">
                  <span v-if="index > 0" class="key-join" aria-hidden="true">/</span>
                  <kbd>{{ key }}</kbd>
                </template>
              </dd>
            </div>
          </dl>
        </section>
      </div>

      <footer class="shortcut-foot">
        <span>按 <kbd>F1</kbd> 可随时打开或关闭此面板。</span>
        <span>所有快捷键均支持键盘焦点导航。</span>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.shortcut-overlay {
  position: fixed;
  inset: 0;
  z-index: 35;
  display: grid;
  padding: 24px;
  background: var(--overlay);
  backdrop-filter: blur(5px);
  place-items: center;
}

.shortcut-dialog {
  width: min(760px, calc(100vw - 48px));
  max-height: calc(100vh - 48px);
  overflow: auto;
  padding: 24px;
  border: 1px solid var(--border-strong);
  border-radius: 14px;
  background: var(--panel-raised);
  box-shadow: var(--shadow-dialog);
}

.shortcut-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 19px;
  border-bottom: 1px solid var(--border);
}

.eyebrow {
  margin: 0 0 6px;
  color: var(--accent-strong);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

h2 {
  margin: 0;
  color: var(--text-strong);
  font-family: var(--font-brand);
  font-size: 24px;
  font-weight: 560;
  letter-spacing: 0.015em;
}

.shortcut-head p:last-child {
  margin: 7px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
}

.close-button {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 7px;
  height: 32px;
  padding: 0 9px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--panel-soft);
  color: var(--text-secondary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.close-button:hover {
  border-color: var(--border-strong);
  background: var(--hover-surface);
  color: var(--text);
}

.close-button:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: 2px;
}

.shortcut-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 22px 28px;
  padding: 22px 0 8px;
}

.shortcut-group h3 {
  margin: 0 0 8px;
  color: var(--text-tertiary);
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.09em;
  text-transform: uppercase;
}

.shortcut-group dl {
  margin: 0;
}

.shortcut-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 34px;
  border-bottom: 1px solid var(--border);
}

.shortcut-row:last-child {
  border-bottom: 0;
}

.shortcut-row dt {
  color: var(--text-secondary);
  font-size: 12px;
}

.shortcut-row dd {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
  margin: 0;
}

.shortcut-row kbd,
.shortcut-foot kbd {
  min-width: 24px;
  padding: 2px 5px;
  text-align: center;
}

.key-join {
  color: var(--text-tertiary);
  font-size: 10px;
}

.shortcut-foot {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px 20px;
  margin-top: 15px;
  padding-top: 15px;
  border-top: 1px solid var(--border);
  color: var(--text-tertiary);
  font-size: 11px;
}

@media (max-width: 720px) {
  .shortcut-overlay {
    padding: 14px;
  }

  .shortcut-dialog {
    width: calc(100vw - 28px);
    max-height: calc(100vh - 28px);
    padding: 18px;
  }

  .shortcut-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .shortcut-head {
    gap: 12px;
  }
}
</style>
