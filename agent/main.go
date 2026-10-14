// nginx-agent는 nginx 컨테이너 안에서 돌며, 관리도구가 부르는 고정된 명령(nginx -t, reload, reopen, -V, 시작)만 대신 실행한다.
// 관리도구에 Docker 소켓이나 셸을 넘기지 않고도 다른 컨테이너의 nginx를 다룰 수 있게 한다.
package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// version은 빌드할 때 -ldflags "-X main.version=1.0.0"으로 넣는다.
var version = "dev"

func main() {
	if len(os.Args) > 1 && os.Args[1] == "version" {
		fmt.Println(version)
		return
	}
	token, err := loadToken(os.Getenv("NGINX_AGENT_TOKEN"),
		env("NGINX_AGENT_TOKEN_FILE", "/var/lib/nginx-manager/agent/token"))
	if err != nil {
		log.Fatalf("agent token: %v", err)
	}
	server := &Server{
		Nginx: &Nginx{
			Binary:         env("NGINX_AGENT_NGINX_BINARY", "/usr/sbin/nginx"),
			PidFile:        env("NGINX_AGENT_PID_FILE", "/run/nginx/nginx.pid"),
			StatusURL:      env("NGINX_AGENT_STATUS_URL", "http://127.0.0.1:8081/nginx_status"),
			ErrorLog:       env("NGINX_AGENT_ERROR_LOG", "/var/log/nginx/error.log"),
			S6Svc:          env("NGINX_AGENT_S6_SVC", "/command/s6-svc"),
			S6Service:      env("NGINX_AGENT_S6_SERVICE", "/run/service/nginx"),
			CommandTimeout: 30 * time.Second,
			ProcDir:        "/proc",
		},
		Token:           token,
		Version:         version,
		BootID:          randomHex(8),
		StartedAt:       time.Now().UTC().Truncate(time.Second),
		StartupCheckDir: env("NGINX_AGENT_STARTUP_CHECK_DIR", "/run/nginx-manager/startup-check"),
	}
	listen := env("NGINX_AGENT_LISTEN", ":7070")
	log.Printf("nginx-agent %s (protocol %d) listening on %s", version, Protocol, listen)
	httpServer := &http.Server{
		Addr:              listen,
		Handler:           server.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Fatal(httpServer.ListenAndServe())
}

// loadToken은 환경 변수의 토큰을 쓰고, 없으면 파일에서 읽는다. 파일도 없으면 새로 만들어 둔다.
// 관리도구 컨테이너는 같은 볼륨에서 이 파일을 읽는다.
func loadToken(fromEnv, file string) (string, error) {
	if t := strings.TrimSpace(fromEnv); t != "" {
		return t, nil
	}
	data, err := os.ReadFile(file)
	if err == nil {
		if t := strings.TrimSpace(string(data)); t != "" {
			return t, nil
		}
	} else if !errors.Is(err, fs.ErrNotExist) {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(file), 0o750); err != nil {
		return "", err
	}
	token := randomHex(32)
	tmp := file + ".tmp"
	if err := os.WriteFile(tmp, []byte(token+"\n"), 0o640); err != nil {
		return "", err
	}
	if err := os.Rename(tmp, file); err != nil {
		return "", err
	}
	log.Printf("created agent token in %s", file)
	return token, nil
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		log.Fatalf("random: %v", err)
	}
	return hex.EncodeToString(b)
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
