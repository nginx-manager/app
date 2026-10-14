package main

import (
	"crypto/subtle"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Protocol은 관리도구와 주고받는 API의 판이다. 요청·응답의 뜻이 바뀌면 올리고, 관리도구도 같이 올린다.
const Protocol = 1

const (
	headerProtocol       = "X-Nginx-Manager-Protocol"
	headerManagerVersion = "X-Nginx-Manager-Version"
	headerAgentProtocol  = "X-Nginx-Agent-Protocol"
	headerAgentVersion   = "X-Nginx-Agent-Version"
	maxTimeout           = 60 * time.Second
)

// Server는 관리도구가 부르는 HTTP API다. /healthz를 뺀 모든 요청은 토큰이 있어야 한다.
type Server struct {
	Nginx           *Nginx
	Token           string
	Version         string
	BootID          string
	StartedAt       time.Time
	StartupCheckDir string

	// 바꾸는 명령(reload·reopen·start)은 하나씩 한다. 관리도구도 잠금 안에서 부르지만 여기서도 막는다.
	mu sync.Mutex
	// 다른 판의 관리도구를 처음 봤을 때만 기록한다
	warned sync.Map
}

type StatusResponse struct {
	AgentVersion string        `json:"agentVersion"`
	Protocol     int           `json:"protocol"`
	BootID       string        `json:"bootId"`
	StartedAt    time.Time     `json:"startedAt"`
	MasterPid    int           `json:"masterPid"`
	NginxV       CommandResult `json:"nginxV"`
}

type ProcessResponse struct {
	BootID    string `json:"bootId"`
	MasterPid int    `json:"masterPid"`
}

type StartupCheckResponse struct {
	Result    string `json:"result"`
	Output    string `json:"output"`
	CheckedAt string `json:"checkedAt"`
}

type errorResponse struct {
	Error         string `json:"error"`
	AgentProtocol int    `json:"agentProtocol,omitempty"`
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"agentVersion": s.Version, "protocol": Protocol})
	})
	mux.HandleFunc("GET /v1/status", s.status)
	mux.HandleFunc("GET /v1/process", s.process)
	mux.HandleFunc("GET /v1/startup-check", s.startupCheck)
	mux.HandleFunc("GET /v1/health", s.health)
	mux.HandleFunc("POST /v1/validate", s.validate)
	mux.HandleFunc("POST /v1/reload", s.reload)
	mux.HandleFunc("POST /v1/reopen", s.reopen)
	mux.HandleFunc("POST /v1/start", s.start)
	return s.guard(mux)
}

// guard는 모든 응답에 에이전트 판을 싣고, 토큰과 프로토콜 판을 확인한다.
func (s *Server) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set(headerAgentVersion, s.Version)
		w.Header().Set(headerAgentProtocol, strconv.Itoa(Protocol))
		if r.URL.Path == "/healthz" {
			next.ServeHTTP(w, r)
			return
		}
		if !s.authorized(r) {
			writeJSON(w, http.StatusUnauthorized, errorResponse{Error: "missing or wrong agent token"})
			return
		}
		if p := r.Header.Get(headerProtocol); p != "" && p != strconv.Itoa(Protocol) {
			writeJSON(w, http.StatusConflict, errorResponse{
				Error:         "nginx-manager protocol " + p + " is not supported by this agent",
				AgentProtocol: Protocol,
			})
			return
		}
		s.noteManagerVersion(r.Header.Get(headerManagerVersion))
		next.ServeHTTP(w, r)
	})
}

func (s *Server) authorized(r *http.Request) bool {
	got, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	return ok && s.Token != "" && subtle.ConstantTimeCompare([]byte(got), []byte(s.Token)) == 1
}

func (s *Server) noteManagerVersion(v string) {
	if v == "" || !versionsDiffer(v, s.Version) {
		return
	}
	if _, seen := s.warned.LoadOrStore(v, true); !seen {
		log.Printf("warning: nginx-manager %s talks to agent %s; run both images at the same version", v, s.Version)
	}
}

// versionsDiffer는 두 릴리스 판이 다른지 본다. 개발 빌드(dev, -SNAPSHOT)는 비교하지 않는다.
func versionsDiffer(a, b string) bool {
	if isDevVersion(a) || isDevVersion(b) {
		return false
	}
	return strings.TrimPrefix(a, "v") != strings.TrimPrefix(b, "v")
}

func isDevVersion(v string) bool {
	return v == "" || v == "dev" || strings.HasSuffix(v, "-SNAPSHOT")
}

func (s *Server) status(w http.ResponseWriter, r *http.Request) {
	resp := StatusResponse{
		AgentVersion: s.Version,
		Protocol:     Protocol,
		BootID:       s.BootID,
		StartedAt:    s.StartedAt,
		MasterPid:    s.Nginx.MasterPid(),
	}
	// 실행 파일이 없거나 응답이 없으면 버전을 모르는 상태로 보여 준다
	if v, err := s.Nginx.Version(); err == nil {
		resp.NginxV = v
	} else {
		resp.NginxV = CommandResult{ExitCode: -1, Output: err.Error()}
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) process(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, ProcessResponse{BootID: s.BootID, MasterPid: s.Nginx.MasterPid()})
}

// startupCheck는 컨테이너가 시작될 때 init-config-check가 남긴 결과다. 아직 없으면 404.
func (s *Server) startupCheck(w http.ResponseWriter, r *http.Request) {
	result, err := os.ReadFile(filepath.Join(s.StartupCheckDir, "result"))
	if err != nil {
		writeJSON(w, http.StatusNotFound, errorResponse{Error: "no startup check result"})
		return
	}
	output, _ := os.ReadFile(filepath.Join(s.StartupCheckDir, "output"))
	checkedAt, _ := os.ReadFile(filepath.Join(s.StartupCheckDir, "checked-at"))
	writeJSON(w, http.StatusOK, StartupCheckResponse{
		Result:    strings.TrimSpace(string(result)),
		Output:    string(output),
		CheckedAt: strings.TrimSpace(string(checkedAt)),
	})
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.Nginx.HealthCheck(timeoutParam(r)))
}

func (s *Server) validate(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	result, err := s.Nginx.Validate()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, errorResponse{Error: err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) reload(w http.ResponseWriter, r *http.Request) {
	s.command(w, func() error { return s.Nginx.Reload(timeoutParam(r)) })
}

func (s *Server) reopen(w http.ResponseWriter, r *http.Request) {
	s.command(w, s.Nginx.ReopenLogs)
}

func (s *Server) start(w http.ResponseWriter, r *http.Request) {
	s.command(w, s.Nginx.Start)
}

func (s *Server) command(w http.ResponseWriter, run func() error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := run(); err != nil {
		writeJSON(w, http.StatusUnprocessableEntity, errorResponse{Error: err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// timeoutParam은 ?timeout=10s 값이다. 없거나 잘못되면 0, 너무 길면 maxTimeout으로 줄인다.
func timeoutParam(r *http.Request) time.Duration {
	d, err := time.ParseDuration(r.URL.Query().Get("timeout"))
	if err != nil || d < 0 {
		return 0
	}
	return min(d, maxTimeout)
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
