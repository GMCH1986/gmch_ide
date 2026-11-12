<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { basicSetup } from 'codemirror'
import { EditorView, keymap } from '@codemirror/view'
import { EditorState, Prec } from '@codemirror/state'
import { java } from '@codemirror/lang-java'
import { oneDark } from '@codemirror/theme-one-dark'

const props = defineProps<{ modelValue: string }>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  cursor: [line: number, col: number]
  save: []
}>()

const host = ref<HTMLDivElement>()
let view: EditorView | null = null
/** 程序化替换文档时置位，避免把外部更新当成用户输入回传 */
let syncing = false

function emitCursor(state: EditorState): void {
  const head = state.selection.main.head
  const line = state.doc.lineAt(head)
  emit('cursor', line.number, head - line.from + 1)
}

onMounted(() => {
  view = new EditorView({
    parent: host.value!,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        Prec.highest(
          keymap.of([
            {
              key: 'Mod-s',
              preventDefault: true,
              run: () => {
                emit('update:modelValue', view?.state.doc.toString() ?? '')
                emit('save')
                return true
              }
            }
          ])
        ),
        basicSetup,
        java(),
        oneDark,
        EditorView.lineWrapping,
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !syncing) {
            emit('update:modelValue', update.state.doc.toString())
          }
          if (update.selectionSet || update.docChanged) {
            emitCursor(update.state)
          }
        })
      ]
    })
  })

  emitCursor(view.state)
})

watch(
  () => props.modelValue,
  (value) => {
    if (!view || value === view.state.doc.toString()) return
    syncing = true
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } })
    syncing = false
  }
)

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})
</script>

<template>
  <div ref="host" class="editor-host" />
</template>

<style scoped>
.editor-host {
  height: 100%;
  overflow: auto;
}
.editor-host :deep(.cm-editor) {
  height: 100%;
}
.editor-host :deep(.cm-scroller) {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 13px;
}
</style>
