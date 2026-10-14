// 화면 공통 동작: diff 줄 색상, 테마(라이트·다크) 전환. 스크립트가 없어도 화면은 그대로 쓸 수 있다.
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

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('pre.diff').forEach(colorDiff);
        document.querySelectorAll('.theme-toggle').forEach(function (button) {
            button.addEventListener('click', toggleTheme);
        });
    });
})();
