// 화면 공통 동작: diff 줄 색상, 테마(라이트·다크) 전환, 좁은 화면의 메뉴 버튼, 변경 이력의 비교 단추, 로그인 화면의 비밀번호 보기. 스크립트가 없어도 화면은 그대로 쓸 수 있다.
(function () {
    // diff를 줄마다 나눠 더한 줄·뺀 줄에 색을 입힌다. 글자는 textContent로만 다룬다
    function colorDiff(pre) {
        const lines = pre.textContent.replace(/\n$/, '').split('\n');
        pre.textContent = '';
        for (const line of lines) {
            const span = document.createElement('span');
            span.className = 'ln';
            if (line.startsWith('+++') || line.startsWith('---')) {
                span.classList.add('meta');
            } else if (line.startsWith('@@')) {
                span.classList.add('hunk');
            } else if (line.startsWith('+')) {
                span.classList.add('add');
            } else if (line.startsWith('-')) {
                span.classList.add('del');
            }
            span.textContent = line;
            pre.appendChild(span);
        }
    }

    // 고른 테마는 이 브라우저에만 기억한다. 고르지 않았으면 시스템 설정을 따른다
    function toggleTheme() {
        const root = document.documentElement;
        const dark = root.dataset.theme
            ? root.dataset.theme === 'dark'
            : window.matchMedia('(prefers-color-scheme: dark)').matches;
        root.dataset.theme = dark ? 'light' : 'dark';
        try {
            localStorage.setItem('nm-theme', root.dataset.theme);
        } catch (e) {
            // 저장할 수 없으면 이번 화면에서만 바꾼다
        }
    }

    // 좁은 화면의 메뉴 버튼: 메뉴를 열고 닫는다. Esc로도 닫는다
    function setupNavToggle(button) {
        const nav = button.closest('.nav');
        function setOpen(open) {
            nav.classList.toggle('open', open);
            button.setAttribute('aria-expanded', open ? 'true' : 'false');
        }
        button.addEventListener('click', function () {
            setOpen(!nav.classList.contains('open'));
        });
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && nav.classList.contains('open')) {
                setOpen(false);
                button.focus();
            }
        });
    }

    // 비밀번호 칸 옆 버튼: 입력한 글자를 보였다 숨긴다. Caps Lock이 켜져 있으면 알린다
    function setupPasswordField(field) {
        const input = field.querySelector('input[type=password]');
        const reveal = field.querySelector('.reveal');
        const caps = field.parentElement.querySelector('.caps-warning');
        if (reveal) {
            reveal.addEventListener('click', function () {
                const shown = input.type === 'password';
                input.type = shown ? 'text' : 'password';
                reveal.setAttribute('aria-pressed', shown ? 'true' : 'false');
                const label = shown ? reveal.dataset.hide : reveal.dataset.show;
                reveal.setAttribute('aria-label', label);
                reveal.title = label;
                input.focus();
            });
            // 보이는 채로 보내지 않도록 보내기 전에 다시 가린다
            input.form.addEventListener('submit', function () {
                input.type = 'password';
            });
        }
        if (caps) {
            const check = function (event) {
                if (event.getModifierState) {
                    caps.hidden = !event.getModifierState('CapsLock');
                }
            };
            input.addEventListener('keydown', check);
            input.addEventListener('keyup', check);
            input.addEventListener('blur', function () {
                caps.hidden = true;
            });
        }
    }

    // htmx 요청이 실패하면(4xx·5xx, 연결 끊김) 아무 반응 없이 끝나지 않도록 보낸 폼 옆에 이유를 보인다
    function showRequestError(event) {
        const form = event.detail.elt.closest('form') || event.detail.elt;
        const xhr = event.detail.xhr;
        const template = document.getElementById('request-failed-text');
        const text = (template ? template.textContent : 'Request failed (HTTP {status})')
            .replace('{status}', xhr && xhr.status ? String(xhr.status) : '-');
        let box = form.querySelector('.request-error');
        if (!box) {
            box = document.createElement('p');
            box.className = 'warning request-error';
            box.setAttribute('role', 'alert');
            form.appendChild(box);
        }
        box.textContent = text;
    }

    // 변경 이력의 비교: 변경 전은 변경 후보다 앞선 버전만 고를 수 있게 나머지 단추를 막는다
    function setupVersionCompare(form) {
        const befores = form.querySelectorAll('input[name=a]');
        const afters = form.querySelectorAll('input[name=b]');
        function update() {
            const before = form.querySelector('input[name=a]:checked');
            const after = form.querySelector('input[name=b]:checked');
            befores.forEach(function (input) {
                input.disabled = after !== null && Number(input.value) >= Number(after.value);
            });
            afters.forEach(function (input) {
                input.disabled = before !== null && Number(input.value) <= Number(before.value);
            });
        }
        form.addEventListener('change', update);
        update();
    }

    document.addEventListener('htmx:responseError', showRequestError);
    document.addEventListener('htmx:sendError', showRequestError);
    document.addEventListener('htmx:beforeRequest', function (event) {
        const form = event.detail.elt.closest('form');
        const box = form && form.querySelector('.request-error');
        if (box) {
            box.remove();
        }
    });
    // 결과는 화면 위쪽에 바뀌어 들어간다. 아래의 버튼을 누른 사람이 결과를 보도록 그리로 옮긴다
    document.addEventListener('htmx:afterSettle', function (event) {
        const result = event.detail.target && event.detail.target.id === 'result'
            ? document.getElementById('result') : null;
        if (result && result.children.length > 0) {
            result.querySelectorAll('pre.diff').forEach(colorDiff);
            result.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    });

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('.auth .field').forEach(function (field) {
            if (field.querySelector('input[type=password]')) {
                setupPasswordField(field);
            }
        });
        document.querySelectorAll('.nav-toggle').forEach(setupNavToggle);
        document.querySelectorAll('form.version-compare').forEach(setupVersionCompare);
        document.querySelectorAll('pre.diff').forEach(colorDiff);
        document.querySelectorAll('.theme-toggle').forEach(function (button) {
            button.addEventListener('click', toggleTheme);
        });
        // 설명서 목차: 좁은 화면에서는 접어 두고 본문부터 보인다
        if (window.matchMedia('(max-width: 860px)').matches) {
            document.querySelectorAll('.guide-toc details').forEach(function (toc) {
                toc.open = false;
            });
        }
    });
})();
