package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	healthRetryInterval = 500 * time.Millisecond
	reloadPollInterval  = 100 * time.Millisecond
	errorLogTail        = 16 * 1024
)

// Nginx는 같은 컨테이너의 nginx를 다룬다. 실행하는 명령은 고정된 인자뿐이며 요청 내용은 인자로 들어가지 않는다.
type Nginx struct {
	Binary         string
	PidFile        string
	StatusURL      string
	ErrorLog       string
	S6Svc          string
	S6Service      string
	CommandTimeout time.Duration
	// ProcDir는 프로세스 정보를 읽는 곳이다. 테스트에서 바꾼다.
	ProcDir string

	httpClient *http.Client
}

type CommandResult struct {
	ExitCode int    `json:"exitCode"`
	Output   string `json:"output"`
}

type HealthResult struct {
	Healthy bool   `json:"healthy"`
	Detail  string `json:"detail"`
}

func (n *Nginx) client() *http.Client {
	if n.httpClient == nil {
		n.httpClient = &http.Client{
			Timeout:   2 * time.Second,
			Transport: &http.Transport{Proxy: nil},
		}
	}
	return n.httpClient
}

// run은 셸을 거치지 않고 명령을 실행해 표준출력과 표준오류를 합쳐 돌려준다.
// 0이 아닌 종료 코드는 오류가 아니라 결과다. 실행 자체를 못 하거나 시간이 넘으면 오류다.
func (n *Nginx) run(name string, args ...string) (CommandResult, error) {
	ctx, cancel := context.WithTimeout(context.Background(), n.CommandTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	err := cmd.Run()
	if ctx.Err() != nil {
		return CommandResult{}, fmt.Errorf("%s timed out after %s", name, n.CommandTimeout)
	}
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
		return CommandResult{ExitCode: exitErr.ExitCode(), Output: out.String()}, nil
	}
	if err != nil {
		return CommandResult{}, fmt.Errorf("failed to run %s: %w", name, err)
	}
	return CommandResult{ExitCode: 0, Output: out.String()}, nil
}

// Version은 nginx -V 결과다. 관리도구가 버전과 모듈을 읽어 낸다.
func (n *Nginx) Version() (CommandResult, error) {
	return n.run(n.Binary, "-V")
}

// Validate는 nginx -t 결과다.
func (n *Nginx) Validate() (CommandResult, error) {
	return n.run(n.Binary, "-t")
}

func (n *Nginx) ReopenLogs() error {
	result, err := n.run(n.Binary, "-s", "reopen")
	if err != nil {
		return err
	}
	if result.ExitCode != 0 {
		return fmt.Errorf("nginx reopen failed: %s", strings.TrimSpace(result.Output))
	}
	return nil
}

// Reload는 reload 신호를 보내고, 마스터가 새 설정으로 워커를 다시 띄웠는지 확인한다.
// nginx -t를 통과해도 포트 충돌처럼 실제로 열어 봐야 아는 문제가 있으면 마스터는 이전 설정을 그대로 쓰고
// 오류만 남기므로, timeout 안에 새 워커가 생기지 않으면 실패로 본다.
func (n *Nginx) Reload(timeout time.Duration) error {
	master := n.MasterPid()
	var before map[int]bool
	if master != 0 {
		before = n.children(master)
	}
	result, err := n.run(n.Binary, "-s", "reload")
	if err != nil {
		return err
	}
	if result.ExitCode != 0 {
		return fmt.Errorf("nginx reload failed: %s", strings.TrimSpace(result.Output))
	}
	if master == 0 || len(before) == 0 {
		return nil
	}
	deadline := time.Now().Add(timeout)
	for {
		for pid := range n.children(master) {
			if !before[pid] {
				return nil
			}
		}
		if !time.Now().Before(deadline) {
			break
		}
		time.Sleep(reloadPollInterval)
	}
	if cause := n.lastEmergency(); cause != "" {
		return fmt.Errorf("nginx가 새 설정을 불러오지 못하고 이전 설정을 계속 씁니다: %s", cause)
	}
	return errors.New("nginx가 새 설정을 불러오지 못하고 이전 설정을 계속 씁니다.")
}

// Start는 내려가 있는 nginx를 s6로 다시 띄운다.
func (n *Nginx) Start() error {
	result, err := n.run(n.S6Svc, "-u", n.S6Service)
	if err != nil {
		return err
	}
	if result.ExitCode != 0 {
		return fmt.Errorf("nginx start failed: %s", strings.TrimSpace(result.Output))
	}
	return nil
}

// HealthCheck는 마스터가 살아 있고 stub_status가 200을 줄 때까지 timeout 동안 다시 확인한다.
func (n *Nginx) HealthCheck(timeout time.Duration) HealthResult {
	deadline := time.Now().Add(timeout)
	for {
		last := n.checkOnce()
		if last.Healthy || !time.Now().Before(deadline) {
			return last
		}
		time.Sleep(healthRetryInterval)
	}
}

func (n *Nginx) checkOnce() HealthResult {
	if n.MasterPid() == 0 {
		return HealthResult{Detail: "nginx master process is not running"}
	}
	resp, err := n.client().Get(n.StatusURL)
	if err != nil {
		return HealthResult{Detail: "status endpoint unreachable: " + err.Error()}
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	if resp.StatusCode != http.StatusOK {
		return HealthResult{Detail: "status endpoint returned " + strconv.Itoa(resp.StatusCode)}
	}
	return HealthResult{Healthy: true, Detail: "ok"}
}

// MasterPid는 살아 있는 nginx 마스터의 PID다. 내려가 있으면 0이다.
// 컨테이너를 다시 띄우면 남은 pid 파일의 번호를 다른 프로세스가 쓰고 있을 수 있어 명령줄까지 확인한다.
func (n *Nginx) MasterPid() int {
	data, err := os.ReadFile(n.PidFile)
	if err != nil {
		return 0
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil || pid <= 0 {
		return 0
	}
	cmdline, err := os.ReadFile(filepath.Join(n.ProcDir, strconv.Itoa(pid), "cmdline"))
	if err != nil || !strings.HasPrefix(string(cmdline), "nginx: master") {
		return 0
	}
	return pid
}

// children은 parent를 부모로 둔 프로세스들이다(/proc/<pid>/stat의 넷째 값).
func (n *Nginx) children(parent int) map[int]bool {
	found := map[int]bool{}
	entries, err := os.ReadDir(n.ProcDir)
	if err != nil {
		return found
	}
	for _, entry := range entries {
		pid, err := strconv.Atoi(entry.Name())
		if err != nil {
			continue
		}
		stat, err := os.ReadFile(filepath.Join(n.ProcDir, entry.Name(), "stat"))
		if err != nil {
			continue
		}
		// 프로세스 이름에 공백이나 괄호가 있을 수 있어 마지막 ')' 뒤부터 읽는다
		s := string(stat)
		end := strings.LastIndexByte(s, ')')
		if end < 0 {
			continue
		}
		fields := strings.Fields(s[end+1:])
		if len(fields) < 2 {
			continue
		}
		if ppid, err := strconv.Atoi(fields[1]); err == nil && ppid == parent {
			found[pid] = true
		}
	}
	return found
}

// lastEmergency는 error.log 끝부분에서 마지막 [emerg] 줄이다. 없거나 읽을 수 없으면 빈 문자열이다.
func (n *Nginx) lastEmergency() string {
	file, err := os.Open(n.ErrorLog)
	if err != nil {
		return ""
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return ""
	}
	start := info.Size() - errorLogTail
	if start < 0 {
		start = 0
	}
	tail := make([]byte, info.Size()-start)
	if _, err := file.ReadAt(tail, start); err != nil && !errors.Is(err, io.EOF) {
		return ""
	}
	return lastEmergencyIn(string(tail))
}

func lastEmergencyIn(log string) string {
	found := ""
	for _, line := range strings.Split(log, "\n") {
		line = strings.TrimRight(line, "\r")
		// 포트 충돌은 재시도 끝에 "still could not bind()"만 남기므로 그 앞의 원인 줄을 쓴다
		i := strings.Index(line, "[emerg]")
		if i < 0 || strings.Contains(line, "still could not bind") {
			continue
		}
		msg := strings.TrimSpace(line[i+len("[emerg]"):])
		// "[emerg] 92#92: bind() ..." 에서 프로세스 번호를 떼어 낸다
		if colon := strings.Index(msg, ": "); colon > 0 && isProcessTag(msg[:colon]) {
			msg = msg[colon+2:]
		}
		found = msg
	}
	return found
}

func isProcessTag(s string) bool {
	parts := strings.Split(s, "#")
	if len(parts) != 2 {
		return false
	}
	for _, p := range parts {
		if _, err := strconv.Atoi(p); err != nil {
			return false
		}
	}
	return true
}
