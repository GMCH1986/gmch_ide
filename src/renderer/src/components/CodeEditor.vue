<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { basicSetup } from 'codemirror'
import { EditorView, keymap } from '@codemirror/view'
import { EditorState, Prec } from '@codemirror/state'
import { java } from '@codemirror/lang-java'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags } from '@lezer/highlight'

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

const graphiteTheme = EditorView.theme(
  {
    '&': { backgroundColor: 'var(--editor-bg)', color: 'var(--text)' },
    '.cm-content': {
      padding: '10px 0',
      caretColor: 'var(--accent-strong)'
    },
    '.cm-scroller': {
      fontFamily: 'var(--font-mono)',
      fontSize: 'var(--editor-font-size)',
      lineHeight: '1.65'
    },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--accent-strong)' },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
      backgroundColor: 'var(--editor-selection)'
    },
    '.cm-gutters': {
      borderRight: '1px solid var(--border)',
      backgroundColor: 'var(--editor-bg)',
      color: 'var(--editor-gutter)'
    },
    '.cm-lineNumbers .cm-gutterElement': { padding: '0 10px 0 8px' },
    '.cm-activeLine': { backgroundColor: 'var(--editor-active-line)' },
    '.cm-activeLineGutter': {
      backgroundColor: 'var(--editor-active-gutter)',
      color: 'var(--text)'
    },
    '.cm-foldPlaceholder': {
      border: '1px solid var(--border-strong)',
      backgroundColor: 'var(--panel-raised)',
      color: 'var(--text-secondary)'
    },
    '.cm-tooltip': {
      border: '1px solid var(--border-strong)',
      backgroundColor: 'var(--panel-raised)',
      color: 'var(--text)'
    },
    '.cm-panels': {
      backgroundColor: 'var(--panel)',
      color: 'var(--text)'
    },
    '.cm-searchMatch': { backgroundColor: 'var(--editor-search-match)' },
    '.cm-searchMatch.cm-searchMatch-selected': {
      backgroundColor: 'var(--editor-search-selected)'
    }
  },
  { dark: true }
)

const graphiteHighlightStyle = HighlightStyle.define([
  { tag: tags.keyword, color: 'var(--syntax-keyword)' },
  { tag: [tags.typeName, tags.className], color: 'var(--syntax-type)' },
  { tag: [tags.name, tags.deleted, tags.character, tags.propertyName], color: 'var(--text)' },
  { tag: [tags.function(tags.variableName), tags.definition(tags.variableName)], color: 'var(--syntax-function)' },
  { tag: [tags.string, tags.special(tags.string)], color: 'var(--syntax-string)' },
  { tag: [tags.number, tags.bool], color: 'var(--syntax-number)' },
  { tag: [tags.comment, tags.lineComment, tags.blockComment], color: 'var(--syntax-comment)', fontStyle: 'italic' },
  { tag: [tags.operator, tags.punctuation], color: 'var(--syntax-operator)' },
  { tag: tags.annotation, color: 'var(--syntax-annotation)' },
  { tag: tags.invalid, color: 'var(--err)', textDecoration: 'underline wavy' }
])

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
        graphiteTheme,
        syntaxHighlighting(graphiteHighlightStyle),
        EditorView.lineWrapping,
        EditorView.contentAttributes.of({
          'aria-label': '代码编辑器',
          spellcheck: 'false'
        }),
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
  background: var(--editor-bg);
}

.editor-host :deep(.cm-editor) {
  height: 100%;
  background: var(--editor-bg);
}

.editor-host :deep(.cm-editor.cm-focused) {
  outline: none;
}

.editor-host :deep(.cm-scroller) {
  font-family: var(--font-mono);
  font-size: var(--editor-font-size);
}
</style>
