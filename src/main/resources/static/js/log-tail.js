// 로그 실시간 보기: SSE로 받은 줄을 아래에 붙인다. 화면에 너무 많이 쌓이면 오래된 줄을 지운다.
(function () {
    const toggle = document.getElementById('live-toggle');
    const panel = document.getElementById('live');
    const lines = document.getElementById('live-lines');
    const state = document.getElementById('live-state');
    if (!toggle) return;
    const MAX_LINES = 2000;
    // 화면 문구는 템플릿이 data-* 속성으로 넣는다(다국어). 없으면 한국어 기본값을 쓴다
    const d = toggle.dataset;
    const text = {
        start: d.labelStart || '실시간 보기',
        stop: d.labelStop || '실시간 멈추기',
        stopped: d.stateStopped || '멈춤',
        connecting: d.stateConnecting || '연결 중',
        watching: d.stateWatching || '보는 중',
        reconnecting: d.stateReconnecting || '다시 연결 중'
    };
    let source = null;

    function stop() {
        if (source) source.close();
        source = null;
        toggle.textContent = text.start;
        state.textContent = text.stopped;
        state.className = 'badge';
    }

    toggle.addEventListener('click', function () {
        if (source) { stop(); return; }
        panel.hidden = false;
        lines.textContent = '';
        state.textContent = text.connecting;
        source = new EventSource(toggle.dataset.stream);
        toggle.textContent = text.stop;
        source.addEventListener('open', function () {
            state.textContent = text.watching;
            state.className = 'badge ok';
        });
        source.addEventListener('line', function (e) {
            const atBottom = lines.scrollTop + lines.clientHeight >= lines.scrollHeight - 4;
            lines.appendChild(document.createTextNode(e.data + '\n'));
            while (lines.childNodes.length > MAX_LINES) lines.removeChild(lines.firstChild);
            if (atBottom) lines.scrollTop = lines.scrollHeight;
        });
        source.addEventListener('busy', function (e) {
            lines.textContent = e.data;
            stop();
        });
        source.addEventListener('error', function () {
            state.textContent = text.reconnecting;
            state.className = 'badge warn';
        });
    });
})();
