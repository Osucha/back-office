// Back Office egress proxy: Stripe Smokescreen with per-job roles (ADR 0024,
// docs/architecture/10-agent-execution.md "Egress").
//
// Two modes, selected with BO_EGRESS_MODE:
//
//   - dynamic (Kubernetes, one Deployment per runner pool): the ACL and the per-job proxy
//     credentials are pulled from the runner-controller (BO_EGRESS_CONTROLLER_URL, bearer
//     BO_EGRESS_CONTROLLER_TOKEN) every BO_EGRESS_REFRESH (default 2s) and immediately when an unknown
//     role shows up. A sandbox authenticates with `Proxy-Authorization: Basic base64(<jobId>:<secret>)`
//     (HTTPS_PROXY=http://<jobId>:<secret>@egress-proxy:4750); the role is the job id and only counts
//     when sha256(secret) matches what the controller registered for that job. Requests without valid
//     credentials fall to the ACL default rule, which the controller renders as "enforce, nothing".
//     Decisions are batched to POST <controller>/egress/v1/decisions.
//   - static (Docker backend, one proxy container per job): the ACL is read once from
//     BO_EGRESS_ACL_FILE; clients need no credentials (the default rule is the job's rule).
//
// In both modes every decision is also printed to stdout as one line `BO-EGRESS {json}` so the
// controller can read it with `docker logs` or `kubectl logs`. All other arguments are Smokescreen's own
// flags (listen port, --deny-range/--allow-range, --allow-address, ...).
package main

import (
	"bytes"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sirupsen/logrus"
	"github.com/stripe/smokescreen/cmd"
	"github.com/stripe/smokescreen/pkg/smokescreen"
	acl "github.com/stripe/smokescreen/pkg/smokescreen/acl/v1"
	"go.yaml.in/yaml/v3"
)

// Decision is one proxy decision as reported to the controller.
type Decision struct {
	Role   string `json:"role"`
	Host   string `json:"host"`
	Allow  bool   `json:"allow"`
	Reason string `json:"reason"`
	At     string `json:"at"`
}

// snapshot is what the controller serves at GET /egress/v1/acl.
type snapshot struct {
	Version     string            `json:"version"`
	ACL         json.RawMessage   `json:"acl"`
	Credentials map[string]string `json:"credentials"` // role -> sha256 hex of the proxy secret
}

type state struct {
	acl   *acl.ACL
	creds map[string]string
	ver   string
}

type dynamicDecider struct {
	current atomic.Pointer[state]
	url     string
	token   string
	client  *http.Client
	log     *logrus.Logger
	mu      sync.Mutex
	last    time.Time
}

func (d *dynamicDecider) Decide(args acl.DecideArgs) (acl.Decision, error) {
	st := d.current.Load()
	if st == nil {
		d.refresh(true)
		st = d.current.Load()
	} else if _, known := st.acl.Rules[args.Service]; args.Service != "" && !known {
		d.refresh(true)
		st = d.current.Load()
	}
	if st == nil {
		return acl.Decision{Result: acl.Deny, Reason: "egress ACL not loaded yet"}, nil
	}
	return st.acl.Decide(args)
}

func (d *dynamicDecider) credential(role string) (string, bool) {
	st := d.current.Load()
	if st == nil || st.creds[role] == "" {
		d.refresh(true)
		st = d.current.Load()
	}
	if st == nil {
		return "", false
	}
	h, ok := st.creds[role]
	return h, ok
}

// refresh loads the snapshot; forced refreshes are rate limited to one per 250 ms.
func (d *dynamicDecider) refresh(forced bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if forced && time.Since(d.last) < 250*time.Millisecond {
		return
	}
	d.last = time.Now()
	req, err := http.NewRequest(http.MethodGet, d.url+"/egress/v1/acl", nil)
	if err != nil {
		d.log.WithError(err).Error("bad controller url")
		return
	}
	req.Header.Set("Authorization", "Bearer "+d.token)
	if st := d.current.Load(); st != nil && st.ver != "" {
		req.Header.Set("If-None-Match", st.ver)
	}
	resp, err := d.client.Do(req)
	if err != nil {
		d.log.WithError(err).Warn("cannot fetch egress ACL from controller")
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotModified {
		return
	}
	if resp.StatusCode != http.StatusOK {
		d.log.WithField("status", resp.StatusCode).Warn("controller refused egress ACL request")
		return
	}
	var snap snapshot
	if err := json.NewDecoder(resp.Body).Decode(&snap); err != nil {
		d.log.WithError(err).Warn("bad egress ACL snapshot")
		return
	}
	parsed, err := parseACL(snap.ACL, d.log)
	if err != nil {
		d.log.WithError(err).Error("controller served an invalid ACL; keeping the previous one")
		return
	}
	d.current.Store(&state{acl: parsed, creds: snap.Credentials, ver: snap.Version})
}

func parseACL(raw []byte, log *logrus.Logger) (*acl.ACL, error) {
	cfg := acl.YAMLConfig{}
	if err := yaml.Unmarshal(raw, &cfg); err != nil {
		return nil, err
	}
	if cfg.Version != "v1" {
		return nil, fmt.Errorf("expected ACL version v1, got %q", cfg.Version)
	}
	parsed, err := cfg.Load()
	if err != nil {
		return nil, err
	}
	parsed.Logger = log
	if err := parsed.Validate(); err != nil {
		return nil, err
	}
	return parsed, nil
}

// roleFromProxyAuth extracts <jobId> from Proxy-Authorization and checks its secret.
func roleFromProxyAuth(d *dynamicDecider) func(*http.Request) (string, error) {
	return func(req *http.Request) (string, error) {
		h := req.Header.Get("Proxy-Authorization")
		if h == "" {
			return "", smokescreen.MissingRoleError("no Proxy-Authorization")
		}
		const prefix = "Basic "
		if !strings.HasPrefix(h, prefix) {
			return "", smokescreen.MissingRoleError("unsupported Proxy-Authorization scheme")
		}
		raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(h[len(prefix):]))
		if err != nil {
			return "", smokescreen.MissingRoleError("malformed Proxy-Authorization")
		}
		user, pass, ok := strings.Cut(string(raw), ":")
		if !ok || user == "" {
			return "", smokescreen.MissingRoleError("malformed Proxy-Authorization")
		}
		expected, known := d.credential(user)
		sum := sha256.Sum256([]byte(pass))
		if !known || subtle.ConstantTimeCompare([]byte(hex.EncodeToString(sum[:])), []byte(expected)) != 1 {
			// Unknown or wrong credentials: treat as anonymous, i.e. the deny-all default rule.
			return "", smokescreen.MissingRoleError("unknown proxy credentials for " + user)
		}
		return user, nil
	}
}

// decisionHook turns Smokescreen's canonical decision log line into a Decision.
type decisionHook struct {
	mu      sync.Mutex
	buf     []Decision
	report  string
	token   string
	client  *http.Client
	stdout  *json.Encoder
	outLock sync.Mutex
}

func (h *decisionHook) Levels() []logrus.Level { return logrus.AllLevels }

func (h *decisionHook) Fire(e *logrus.Entry) error {
	if e.Message != smokescreen.CanonicalProxyDecision {
		return nil
	}
	d := Decision{At: e.Time.UTC().Format(time.RFC3339Nano)}
	if v, ok := e.Data[smokescreen.LogFieldRole].(string); ok {
		d.Role = v
	}
	if v, ok := e.Data[smokescreen.LogFieldRequestedHost].(string); ok {
		d.Host = v
	}
	if v, ok := e.Data[smokescreen.LogFieldAllow].(bool); ok {
		d.Allow = v
	}
	if v, ok := e.Data[smokescreen.LogFieldDecisionReason].(string); ok {
		d.Reason = v
	}
	h.outLock.Lock()
	fmt.Fprint(os.Stdout, "BO-EGRESS ")
	_ = h.stdout.Encode(d)
	h.outLock.Unlock()
	if h.report != "" {
		h.mu.Lock()
		h.buf = append(h.buf, d)
		h.mu.Unlock()
	}
	return nil
}

func (h *decisionHook) flushLoop() {
	for range time.Tick(time.Second) {
		h.mu.Lock()
		batch := h.buf
		h.buf = nil
		h.mu.Unlock()
		if len(batch) == 0 {
			continue
		}
		body, _ := json.Marshal(batch)
		req, err := http.NewRequest(http.MethodPost, h.report+"/egress/v1/decisions", bytes.NewReader(body))
		if err != nil {
			continue
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+h.token)
		resp, err := h.client.Do(req)
		if err != nil || resp.StatusCode >= 300 {
			// Put the batch back (bounded) so a controller restart does not lose evidence.
			h.mu.Lock()
			if len(h.buf) < 10000 {
				h.buf = append(batch, h.buf...)
			}
			h.mu.Unlock()
		}
		if resp != nil {
			resp.Body.Close()
		}
	}
}

func main() {
	logger := logrus.New()
	logger.SetFormatter(&logrus.JSONFormatter{TimestampFormat: time.RFC3339Nano})
	logger.SetOutput(os.Stderr)

	mode := strings.ToLower(os.Getenv("BO_EGRESS_MODE"))
	if mode == "" {
		mode = "static"
	}
	hook := &decisionHook{client: &http.Client{Timeout: 5 * time.Second}, stdout: json.NewEncoder(os.Stdout)}
	logger.AddHook(hook)

	conf, err := cmd.NewConfiguration(nil, logger)
	if err != nil {
		logger.Fatal(err)
	}
	if conf == nil {
		os.Exit(1)
	}

	switch mode {
	case "dynamic":
		base := strings.TrimRight(os.Getenv("BO_EGRESS_CONTROLLER_URL"), "/")
		token := os.Getenv("BO_EGRESS_CONTROLLER_TOKEN")
		if base == "" || token == "" {
			logger.Fatal("dynamic mode needs BO_EGRESS_CONTROLLER_URL and BO_EGRESS_CONTROLLER_TOKEN")
		}
		refresh := 2 * time.Second
		if v := os.Getenv("BO_EGRESS_REFRESH"); v != "" {
			if parsed, perr := time.ParseDuration(v); perr == nil {
				refresh = parsed
			}
		}
		d := &dynamicDecider{url: base, token: token, client: &http.Client{Timeout: 5 * time.Second}, log: logger}
		d.refresh(false)
		go func() {
			for range time.Tick(refresh) {
				d.refresh(false)
			}
		}()
		conf.EgressACL = d
		conf.RoleFromRequest = roleFromProxyAuth(d)
		conf.AllowMissingRole = true // anonymous -> default rule (deny all)
		hook.report, hook.token = base, token
		go hook.flushLoop()
	case "static":
		path := os.Getenv("BO_EGRESS_ACL_FILE")
		if path == "" {
			logger.Fatal("static mode needs BO_EGRESS_ACL_FILE")
		}
		raw, rerr := os.ReadFile(path)
		if rerr != nil {
			logger.Fatal(rerr)
		}
		parsed, perr := parseACL(raw, logger)
		if perr != nil {
			logger.Fatal(perr)
		}
		conf.EgressACL = parsed
		conf.AllowMissingRole = true
	default:
		logger.Fatalf("unknown BO_EGRESS_MODE %q", mode)
	}

	smokescreen.StartWithConfig(conf, nil)
}
