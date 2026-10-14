package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fakeNginx는 받은 인자에 따라 정해진 출력을 내는 nginx 대역이다.
func fakeNginx(t *testing.T, script string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "nginx")
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"+script), 0o755); err != nil {
		t.Fatal(err)
	}
	return path
}

func newServer(t *testing.T, nginxScript string) *Server {
	t.Helper()
	return &Server{
		Nginx: &Nginx{
			Binary:         fakeNginx(t, nginxScript),
			PidFile:        filepath.Join(t.TempDir(), "missing.pid"),
			StatusURL:      "http://127.0.0.1:1/nginx_status",
			ErrorLog:       filepath.Join(t.TempDir(), "error.log"),
			CommandTimeout: 5 * time.Second,
			ProcDir:        t.TempDir(),
		},
		Token:           "secret",
		Version:         "1.0.0",
		BootID:          "boot",
		StartupCheckDir: t.TempDir(),
	}
}

func do(t *testing.T, s *Server, method, path, token string, headers map[string]string) (*httptest.ResponseRecorder, map[string]any) {
	t.Helper()
	req := httptest.NewRequest(method, path, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, req)
	body := map[string]any{}
	_ = json.Unmarshal(rec.Body.Bytes(), &body)
	return rec, body
}

func TestHealthzNeedsNoTokenOtherEndpointsDo(t *testing.T) {
	s := newServer(t, "exit 0")
	rec, _ := do(t, s, "GET", "/healthz", "", nil)
	if rec.Code != 200 || rec.Header().Get(headerAgentVersion) != "1.0.0" {
		t.Fatalf("healthz: %d %v", rec.Code, rec.Header())
	}
	for _, token := range []string{"", "wrong"} {
		rec, _ = do(t, s, "POST", "/v1/validate", token, nil)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("token %q: got %d", token, rec.Code)
		}
	}
}

func TestOtherProtocolIsRejected(t *testing.T) {
	s := newServer(t, "exit 0")
	rec, body := do(t, s, "GET", "/v1/process", "secret", map[string]string{headerProtocol: "99"})
	if rec.Code != http.StatusConflict || body["agentProtocol"] != float64(Protocol) {
		t.Fatalf("got %d %v", rec.Code, body)
	}
	rec, _ = do(t, s, "GET", "/v1/process", "secret", map[string]string{headerProtocol: "1"})
	if rec.Code != 200 {
		t.Fatalf("same protocol: %d", rec.Code)
	}
}

func TestValidateReturnsExitCodeAndOutput(t *testing.T) {
	s := newServer(t, `echo "nginx: [emerg] unknown directive \"foo\" in /etc/nginx/nginx.conf:3" >&2; exit 1`)
	rec, body := do(t, s, "POST", "/v1/validate", "secret", nil)
	if rec.Code != 200 || body["exitCode"] != float64(1) || !strings.Contains(body["output"].(string), "unknown directive") {
		t.Fatalf("got %d %v", rec.Code, body)
	}
}

func TestStatusIncludesNginxVAndStoppedMaster(t *testing.T) {
	s := newServer(t, `echo "nginx version: nginx/1.31.6" >&2`)
	_, body := do(t, s, "GET", "/v1/status", "secret", nil)
	nginxV := body["nginxV"].(map[string]any)
	if body["masterPid"] != float64(0) || !strings.Contains(nginxV["output"].(string), "1.31.6") || body["bootId"] != "boot" {
		t.Fatalf("got %v", body)
	}
}

func TestFailedReloadIsReported(t *testing.T) {
	s := newServer(t, `echo 'nginx: [error] invalid PID number "" in "/run/nginx/nginx.pid"' >&2; exit 1`)
	rec, body := do(t, s, "POST", "/v1/reload?timeout=1s", "secret", nil)
	if rec.Code != http.StatusUnprocessableEntity || !strings.Contains(body["error"].(string), "invalid PID") {
		t.Fatalf("got %d %v", rec.Code, body)
	}
}

func TestStartupCheckIsReadFromInitScriptOutput(t *testing.T) {
	s := newServer(t, "exit 0")
	rec, _ := do(t, s, "GET", "/v1/startup-check", "secret", nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("before check: %d", rec.Code)
	}
	write(t, filepath.Join(s.StartupCheckDir, "result"), "failed\n")
	write(t, filepath.Join(s.StartupCheckDir, "output"), "nginx: [emerg] x\n")
	write(t, filepath.Join(s.StartupCheckDir, "checked-at"), "2026-10-13T01:00:00Z\n")
	_, body := do(t, s, "GET", "/v1/startup-check", "secret", nil)
	if body["result"] != "failed" || body["checkedAt"] != "2026-10-13T01:00:00Z" {
		t.Fatalf("got %v", body)
	}
}

func TestMasterPidChecksTheProcessIsNginxMaster(t *testing.T) {
	s := newServer(t, "exit 0")
	n := s.Nginx
	write(t, n.PidFile, "42\n")
	if n.MasterPid() != 0 {
		t.Fatal("pid without a process must be ignored")
	}
	write(t, filepath.Join(n.ProcDir, "42", "cmdline"), "java\x00-jar")
	if n.MasterPid() != 0 {
		t.Fatal("a reused pid must be ignored")
	}
	write(t, filepath.Join(n.ProcDir, "42", "cmdline"), "nginx: master process /usr/sbin/nginx")
	write(t, filepath.Join(n.ProcDir, "43", "stat"), "43 (nginx) S 42 1 1")
	write(t, filepath.Join(n.ProcDir, "44", "stat"), "44 (some (odd) name) S 42 1 1")
	write(t, filepath.Join(n.ProcDir, "45", "stat"), "45 (sh) S 1 1 1")
	if n.MasterPid() != 42 {
		t.Fatalf("got %d", n.MasterPid())
	}
	children := n.children(42)
	if len(children) != 2 || !children[43] || !children[44] {
		t.Fatalf("got %v", children)
	}
}

func TestLastEmergencySkipsBindRetrySummary(t *testing.T) {
	log := "2026/10/13 [notice] 1#1: signal process started\n" +
		"2026/10/13 [emerg] 92#92: bind() to 0.0.0.0:8080 failed (98: Address in use)\n" +
		"2026/10/13 [emerg] 92#92: still could not bind()\n"
	if got := lastEmergencyIn(log); got != "bind() to 0.0.0.0:8080 failed (98: Address in use)" {
		t.Fatalf("got %q", got)
	}
}

func TestTokenIsCreatedOnceAndReused(t *testing.T) {
	file := filepath.Join(t.TempDir(), "agent", "token")
	first, err := loadToken("", file)
	if err != nil || len(first) != 64 {
		t.Fatalf("got %q %v", first, err)
	}
	info, _ := os.Stat(file)
	if info.Mode().Perm() != 0o640 {
		t.Fatalf("mode %v", info.Mode())
	}
	second, _ := loadToken("", file)
	if second != first {
		t.Fatal("token must be reused")
	}
	fromEnv, _ := loadToken(" given ", file)
	if fromEnv != "given" {
		t.Fatalf("got %q", fromEnv)
	}
}

func TestDevelopmentVersionsAreNotCompared(t *testing.T) {
	cases := []struct {
		a, b string
		want bool
	}{
		{"1.0.0", "1.0.0", false},
		{"v1.0.0", "1.0.0", false},
		{"1.0.0", "1.1.0", true},
		{"dev", "1.1.0", false},
		{"0.1.0-SNAPSHOT", "dev", false},
	}
	for _, c := range cases {
		if got := versionsDiffer(c.a, c.b); got != c.want {
			t.Errorf("versionsDiffer(%q, %q) = %v", c.a, c.b, got)
		}
	}
}

// runningMaster는 ProcDir에 42번 nginx 마스터와 주어진 워커들을 만든다.
func runningMaster(t *testing.T, n *Nginx, workers ...string) {
	t.Helper()
	write(t, n.PidFile, "42\n")
	write(t, filepath.Join(n.ProcDir, "42", "cmdline"), "nginx: master process /usr/sbin/nginx")
	for _, pid := range workers {
		write(t, filepath.Join(n.ProcDir, pid, "stat"), pid+" (nginx) S 42 1 1")
	}
}

// spawnWorker는 reload를 받으면 delay 뒤에 마스터 42의 새 워커 pid를 ProcDir에 만드는 nginx 스크립트다.
func spawnWorker(procDir, pid, delay string) string {
	return `if [ "$2" = reload ]; then (sleep ` + delay + `; mkdir -p ` + procDir + `/` + pid +
		`; echo "` + pid + ` (nginx) S 42 1 1" > ` + procDir + `/` + pid + `/stat) >/dev/null 2>&1 & fi`
}

func TestReloadWaitsForNewWorker(t *testing.T) {
	s := newServer(t, "exit 0")
	runningMaster(t, s.Nginx, "43")
	// 첫 확인 때는 아직 없고 몇 번 뒤에 생기는 워커를 찾아야 한다
	s.Nginx.Binary = fakeNginx(t, spawnWorker(s.Nginx.ProcDir, "50", "0.3"))
	started := time.Now()
	rec, body := do(t, s, "POST", "/v1/reload?timeout=5s", "secret", nil)
	if rec.Code != 200 || body["ok"] != true {
		t.Fatalf("got %d %v", rec.Code, body)
	}
	if elapsed := time.Since(started); elapsed < 300*time.Millisecond || elapsed > 3*time.Second {
		t.Fatalf("elapsed %s", elapsed)
	}
}

func TestReloadWithoutNewWorkerFailsWithNewEmergencyOnly(t *testing.T) {
	s := newServer(t, "exit 0")
	n := s.Nginx
	runningMaster(t, n, "43")
	// 앞선 실패가 남긴 [emerg]는 이번 원인이 아니다
	write(t, n.ErrorLog, "2026/10/13 [emerg] 92#92: unknown directive \"old\"\n")
	rec, body := do(t, s, "POST", "/v1/reload?timeout=200ms", "secret", nil)
	msg, _ := body["error"].(string)
	if rec.Code != http.StatusUnprocessableEntity || strings.Contains(msg, "old") || !strings.Contains(msg, "이전 설정") {
		t.Fatalf("got %d %v", rec.Code, body)
	}

	n.Binary = fakeNginx(t, `echo "2026/10/13 [emerg] 93#93: bind() to 0.0.0.0:8080 failed (98: Address in use)" >> `+n.ErrorLog)
	rec, body = do(t, s, "POST", "/v1/reload?timeout=200ms", "secret", nil)
	msg, _ = body["error"].(string)
	if rec.Code != http.StatusUnprocessableEntity || !strings.Contains(msg, "bind() to 0.0.0.0:8080") || strings.Contains(msg, "old") {
		t.Fatalf("got %d %v", rec.Code, body)
	}
}

func TestLastEmergencyReadsWholeLogAfterRotation(t *testing.T) {
	s := newServer(t, "exit 0")
	n := s.Nginx
	write(t, n.ErrorLog, "2026/10/13 [emerg] 1#1: fresh\n")
	if got := n.lastEmergency(1 << 20); got != "fresh" {
		t.Fatalf("got %q", got)
	}
	if got := n.lastEmergency(n.errorLogSize()); got != "" {
		t.Fatalf("got %q", got)
	}
}

func TestReloadOfMasterWithoutWorkersIsVerified(t *testing.T) {
	s := newServer(t, "exit 0")
	runningMaster(t, s.Nginx)
	rec, _ := do(t, s, "POST", "/v1/reload?timeout=200ms", "secret", nil)
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("no worker ever appeared: %d", rec.Code)
	}
	s.Nginx.Binary = fakeNginx(t, spawnWorker(s.Nginx.ProcDir, "50", "0.1"))
	rec, body := do(t, s, "POST", "/v1/reload?timeout=5s", "secret", nil)
	if rec.Code != 200 {
		t.Fatalf("got %d %v", rec.Code, body)
	}
}

func TestTimeoutParamDefaultsAndCaps(t *testing.T) {
	cases := map[string]time.Duration{
		"":                defaultTimeout,
		"?timeout=abc":    defaultTimeout,
		"?timeout=-1s":    defaultTimeout,
		"?timeout=0ms":    0,
		"?timeout=1500ms": 1500 * time.Millisecond,
		"?timeout=5m":     maxTimeout,
	}
	for query, want := range cases {
		if got := timeoutParam(httptest.NewRequest("POST", "/v1/reload"+query, nil)); got != want {
			t.Errorf("%q: got %s want %s", query, got, want)
		}
	}
}

func TestChangingCommandsRunOneAtATime(t *testing.T) {
	marks := filepath.Join(t.TempDir(), "marks")
	script := `echo "start $*" >> ` + marks + `; sleep 0.1; echo "end $*" >> ` + marks
	s := newServer(t, script)
	s.Nginx.S6Svc = fakeNginx(t, script)
	s.Nginx.S6Service = "/run/service/nginx"
	paths := []string{"/v1/validate", "/v1/reload?timeout=0s", "/v1/reopen", "/v1/start"}
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		for _, path := range paths {
			wg.Add(1)
			go func() {
				defer wg.Done()
				if rec, body := do(t, s, "POST", path, "secret", nil); rec.Code != 200 {
					t.Errorf("%s: %d %v", path, rec.Code, body)
				}
			}()
		}
	}
	wg.Wait()
	data, err := os.ReadFile(marks)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 2*2*len(paths) {
		t.Fatalf("got %d lines:\n%s", len(lines), data)
	}
	for i := 0; i < len(lines); i += 2 {
		args, ok := strings.CutPrefix(lines[i], "start ")
		if !ok || lines[i+1] != "end "+args {
			t.Fatalf("interleaved at line %d:\n%s", i, data)
		}
	}
}

func TestCommandThatCannotRunIs500(t *testing.T) {
	s := newServer(t, "exit 0")
	s.Nginx.Binary = filepath.Join(t.TempDir(), "missing")
	rec, body := do(t, s, "POST", "/v1/validate", "secret", nil)
	if rec.Code != http.StatusInternalServerError || !strings.Contains(body["error"].(string), "failed to run") {
		t.Fatalf("got %d %v", rec.Code, body)
	}

	// sleep이 출력을 붙잡고 있어도 CommandTimeout 뒤 곧 돌아와야 한다
	s.Nginx.Binary = fakeNginx(t, "sleep 10")
	s.Nginx.CommandTimeout = 200 * time.Millisecond
	started := time.Now()
	rec, body = do(t, s, "POST", "/v1/validate", "secret", nil)
	if rec.Code != http.StatusInternalServerError || !strings.Contains(body["error"].(string), "timed out") {
		t.Fatalf("got %d %v", rec.Code, body)
	}
	if elapsed := time.Since(started); elapsed > 3*time.Second {
		t.Fatalf("elapsed %s", elapsed)
	}
}

func TestReopenAndStart(t *testing.T) {
	s := newServer(t, `[ "$1 $2" = "-s reopen" ] || exit 9`)
	s.Nginx.S6Svc = fakeNginx(t, `[ "$1" = -u ] && [ "$2" = /run/service/nginx ] || exit 9`)
	s.Nginx.S6Service = "/run/service/nginx"
	for _, path := range []string{"/v1/reopen", "/v1/start"} {
		rec, body := do(t, s, "POST", path, "secret", nil)
		if rec.Code != 200 || body["ok"] != true {
			t.Fatalf("%s: %d %v", path, rec.Code, body)
		}
	}

	s.Nginx.Binary = fakeNginx(t, `echo "nginx: [alert] could not open error log" >&2; exit 1`)
	s.Nginx.S6Svc = fakeNginx(t, `echo "s6-svc: fatal: unable to control /run/service/nginx" >&2; exit 111`)
	for path, want := range map[string]string{"/v1/reopen": "could not open error log", "/v1/start": "unable to control"} {
		rec, body := do(t, s, "POST", path, "secret", nil)
		if rec.Code != http.StatusUnprocessableEntity || !strings.Contains(body["error"].(string), want) {
			t.Fatalf("%s: %d %v", path, rec.Code, body)
		}
	}
}

func TestHealth(t *testing.T) {
	var calls atomic.Int32
	var failFirst atomic.Int32
	stub := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) <= failFirst.Load() {
			w.WriteHeader(http.StatusInternalServerError)
		}
	}))
	defer stub.Close()
	s := newServer(t, "exit 0")
	s.Nginx.StatusURL = stub.URL

	_, body := do(t, s, "GET", "/v1/health?timeout=0s", "secret", nil)
	if body["healthy"] != false || !strings.Contains(body["detail"].(string), "not running") || calls.Load() != 0 {
		t.Fatalf("dead master: %v", body)
	}

	runningMaster(t, s.Nginx, "43")
	_, body = do(t, s, "GET", "/v1/health?timeout=0s", "secret", nil)
	if body["healthy"] != true || body["detail"] != "ok" {
		t.Fatalf("healthy: %v", body)
	}

	calls.Store(0)
	failFirst.Store(1000)
	_, body = do(t, s, "GET", "/v1/health?timeout=0s", "secret", nil)
	if body["healthy"] != false || body["detail"] != "status endpoint returned 500" {
		t.Fatalf("500: %v", body)
	}

	// 처음 두 번은 500, 그 뒤로 200이면 timeout 안에 다시 확인해 정상이 된다
	calls.Store(0)
	failFirst.Store(2)
	_, body = do(t, s, "GET", "/v1/health?timeout=5s", "secret", nil)
	if body["healthy"] != true || calls.Load() != 3 {
		t.Fatalf("retry: %v after %d calls", body, calls.Load())
	}

	// 끝까지 500이면 timeout까지 다시 확인하고 마지막 결과를 준다
	calls.Store(0)
	failFirst.Store(1000)
	started := time.Now()
	_, body = do(t, s, "GET", "/v1/health?timeout=600ms", "secret", nil)
	if body["healthy"] != false || calls.Load() < 2 || time.Since(started) < 600*time.Millisecond {
		t.Fatalf("deadline: %v after %d calls", body, calls.Load())
	}
}

func TestAuthorizationEdges(t *testing.T) {
	s := newServer(t, "exit 0")
	call := func(auth string, headers map[string]string) int {
		req := httptest.NewRequest("GET", "/v1/process", nil)
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		rec := httptest.NewRecorder()
		s.Handler().ServeHTTP(rec, req)
		return rec.Code
	}
	cases := map[string]int{
		"Basic secret":   http.StatusUnauthorized,
		"Basic c2VjcmV0": http.StatusUnauthorized,
		"Bearer":         http.StatusUnauthorized,
		"Bearer  secret": http.StatusUnauthorized,
		"secret":         http.StatusUnauthorized,
		"bearer secret":  http.StatusOK,
		"BEARER secret":  http.StatusOK,
		"Bearer secret":  http.StatusOK,
	}
	for auth, want := range cases {
		if got := call(auth, nil); got != want {
			t.Errorf("%q: got %d want %d", auth, got, want)
		}
	}

	// 토큰이 틀리면 프로토콜보다 401이 먼저다
	if got := call("Bearer wrong", map[string]string{headerProtocol: "99"}); got != http.StatusUnauthorized {
		t.Errorf("wrong token and protocol: %d", got)
	}
	// 프로토콜 헤더가 없으면 받아들인다(계약은 다른 판만 거부한다)
	if got := call("Bearer secret", nil); got != http.StatusOK {
		t.Errorf("missing protocol: %d", got)
	}
	// /healthz는 토큰도 프로토콜도 보지 않는다
	rec, _ := do(t, s, "GET", "/healthz", "", map[string]string{headerProtocol: "99"})
	if rec.Code != 200 {
		t.Errorf("healthz with other protocol: %d", rec.Code)
	}

	// 서버 토큰이 비어 있으면 무엇을 보내도 401이다
	s.Token = ""
	for _, auth := range []string{"", "Bearer", "Bearer ", "Bearer secret"} {
		if got := call(auth, nil); got != http.StatusUnauthorized {
			t.Errorf("empty server token, %q: %d", auth, got)
		}
	}
}

func TestEmptyTokenFileIsRegeneratedAndUnreadableFails(t *testing.T) {
	file := filepath.Join(t.TempDir(), "token")
	write(t, file, " \n")
	token, err := loadToken("", file)
	if err != nil || len(token) != 64 {
		t.Fatalf("got %q %v", token, err)
	}
	if data, _ := os.ReadFile(file); strings.TrimSpace(string(data)) != token {
		t.Fatalf("file has %q", data)
	}

	// 읽을 수 없는 파일(여기서는 디렉터리)은 덮어쓰지 않고 오류로 끝낸다
	dir := filepath.Join(t.TempDir(), "token")
	if err := os.Mkdir(dir, 0o750); err != nil {
		t.Fatal(err)
	}
	if token, err := loadToken("", dir); err == nil {
		t.Fatalf("got %q", token)
	}
}

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
