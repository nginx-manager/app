package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
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

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
