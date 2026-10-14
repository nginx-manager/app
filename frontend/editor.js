// 원문 편집기(FR-003): textarea.editor를 nginx 문법 강조가 있는 CodeMirror 6 편집기로 바꾼다.
// 스크립트가 없어도 textarea로 그대로 쓸 수 있다. 저장할 때는 편집기 내용을 textarea에 되돌려 보낸다.
import {basicSetup} from 'codemirror';
import {EditorView, keymap} from '@codemirror/view';
import {EditorState} from '@codemirror/state';
import {StreamLanguage} from '@codemirror/language';
import {indentWithTab} from '@codemirror/commands';
import {nginx} from '@codemirror/legacy-modes/mode/nginx';

function attach(textarea) {
    const readOnly = textarea.readOnly;
    const view = new EditorView({
        state: EditorState.create({
            doc: textarea.value,
            extensions: [
                basicSetup,
                keymap.of([indentWithTab]),
                StreamLanguage.define(nginx),
                EditorState.readOnly.of(readOnly),
                EditorView.editable.of(!readOnly),
                EditorView.theme({'&': {border: '1px solid #d0d5dd', borderRadius: '6px'},
                    '.cm-scroller': {fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', minHeight: '24rem', maxHeight: '70vh'}}),
            ],
        }),
    });
    textarea.after(view.dom);
    textarea.hidden = true;
    if (textarea.form) {
        textarea.form.addEventListener('submit', () => {
            textarea.value = view.state.doc.toString();
        });
    }
    // 검증 오류에서 #L12 처럼 줄을 가리키면 그 줄로 간다
    const match = location.hash.match(/^#L(\d+)$/);
    if (match) {
        const number = Math.min(Number(match[1]), view.state.doc.lines);
        const line = view.state.doc.line(number);
        view.dispatch({selection: {anchor: line.from, head: line.to}, scrollIntoView: true});
        view.focus();
    }
}

document.querySelectorAll('textarea.editor').forEach(attach);
