// 화면 공통 동작: diff 줄 색상, 테마(라이트·다크) 전환, 좁은 화면의 메뉴 버튼, 로그인 화면의 비밀번호 보기. 스크립트가 없어도 화면은 그대로 쓸 수 있다.
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

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('.auth .field').forEach(function (field) {
            if (field.querySelector('input[type=password]')) {
                setupPasswordField(field);
            }
        });
        document.querySelectorAll('.nav-toggle').forEach(setupNavToggle);
        document.querySelectorAll('pre.diff').forEach(colorDiff);
        document.querySelectorAll('.theme-toggle').forEach(function (button) {
            button.addEventListener('click', toggleTheme);
        });
    });
})();
