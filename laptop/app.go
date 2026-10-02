package main

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

type App struct {
	mu      sync.Mutex
	ctx     context.Context
	cmd     *exec.Cmd
	device  string
	alive   bool
	started time.Time

	logMu  sync.Mutex
	logBuf []string
}

type Status struct {
	Running bool   `json:"running"`
	Uptime  string `json:"uptime"`
	Device  string `json:"device"`
	Log     string `json:"log"`
}

func NewApp() *App {
	return &App{}
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
}

func (a *App) shutdown(ctx context.Context) {
	a.StopTunnel()
}

func (a *App) GetPlatform() string {
	return runtime.GOOS
}

func (a *App) appendLog(line string) {
	line = strings.TrimRight(line, "\n")
	if line == "" {
		return
	}
	a.logMu.Lock()
	a.logBuf = append(a.logBuf, line)
	if len(a.logBuf) > 300 {
		a.logBuf = a.logBuf[len(a.logBuf)-300:]
	}
	a.logMu.Unlock()
}

func (a *App) Log() string {
	a.logMu.Lock()
	defer a.logMu.Unlock()
	return strings.Join(a.logBuf, "\n")
}

func (a *App) Status() Status {
	a.mu.Lock()
	defer a.mu.Unlock()
	st := Status{Running: a.alive, Device: a.device}
	if a.alive {
		st.Uptime = time.Since(a.started).Round(time.Second).String()
	}
	a.logMu.Lock()
	st.Log = strings.Join(a.logBuf, "\n")
	a.logMu.Unlock()
	return st
}

func (a *App) StartTunnel(proxy, password, device string, mtu int) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.alive {
		return fmt.Errorf("tunnel already running")
	}
	proxy = strings.TrimSpace(proxy)
	device = strings.TrimSpace(device)
	if proxy == "" {
		return fmt.Errorf("phone address is required, e.g. 192.168.137.2:12345")
	}
	if device == "" {
		device = "celltun"
	}
	if mtu <= 0 {
		mtu = 1400
	}

	exe, err := os.Executable()
	if err != nil {
		return fmt.Errorf("cannot resolve executable: %w", err)
	}
	args := []string{"-proxy", proxy, "-device", device, "-mtu", strconv.Itoa(mtu)}
	if strings.TrimSpace(password) != "" {
		args = append(args, "-pass", password)
	}
	cmd := exec.Command(exe, args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("failed to start tunnel process: %w", err)
	}
	a.cmd = cmd
	a.device = device
	a.alive = true
	a.started = time.Now()
	go func() {
		sc := bufio.NewScanner(stdout)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			a.appendLog(sc.Text())
		}
	}()
	go func() {
		sc := bufio.NewScanner(stderr)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			a.appendLog(sc.Text())
		}
	}()
	go a.reap(cmd)

	time.Sleep(900)
	if !a.alive {
		a.logMu.Lock()
		tail := strings.Join(a.logBuf[len(a.logBuf)-8:], "\n")
		a.logMu.Unlock()
		return fmt.Errorf("tunnel exited right after start: %s", strings.TrimSpace(tail))
	}
	return nil
}

func (a *App) reap(cmd *exec.Cmd) {
	err := cmd.Wait()
	a.mu.Lock()
	wasAlive := a.alive && a.cmd == cmd
	a.alive = false
	a.mu.Unlock()
	if wasAlive {
		if err != nil {
			a.appendLog("tunnel process exited: " + err.Error())
		} else {
			a.appendLog("tunnel process stopped")
		}
	}
}

func (a *App) StopTunnel() error {
	a.mu.Lock()
	cmd := a.cmd
	a.mu.Unlock()
	if cmd == nil || cmd.Process == nil {
		return nil
	}
	done := make(chan struct{})
	go func() {
		cmd.Wait()
		close(done)
	}()
	if runtime.GOOS == "windows" {
		_ = cmd.Process.Kill()
	} else {
		_ = cmd.Process.Signal(syscall.SIGTERM)
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			_ = cmd.Process.Kill()
		}
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
	}
	return nil
}

func (a *App) setupCommand(root bool, name string, args ...string) *exec.Cmd {
	if runtime.GOOS == "linux" && !root && name != "sh" {
		return exec.Command("sudo", append([]string{name}, args...)...)
	}
	return exec.Command(name, args...)
}

func (a *App) isRoot() bool {
	return os.Getuid() == 0
}

func (a *App) ApplyNetworkSetup() (string, error) {
	a.mu.Lock()
	dev := a.device
	if dev == "" {
		dev = "celltun"
	}
	a.mu.Unlock()

	var out strings.Builder
	if runtime.GOOS == "linux" {
		if !a.isRoot() {
			return "", fmt.Errorf("this needs root: launch the app with sudo, or run the commands from the guide manually")
		}
		cmds := [][]string{
			{"ip", "addr", "add", "198.18.0.1/32", "dev", dev},
			{"ip", "route", "replace", "default", "dev", dev},
			{"sh", "-c", "echo 'nameserver 1.1.1.1' > /etc/resolv.conf"},
		}
		for _, c := range cmds {
			o, err := exec.Command(c[0], c[1:]...).CombinedOutput()
			msg := strings.TrimSpace(string(o))
			if err != nil {
				if msg != "" {
					out.WriteString(msg + "\n")
				}
				return out.String(), fmt.Errorf("%s: %w", strings.Join(c, " "), err)
			}
			if msg != "" {
				out.WriteString(msg + "\n")
			}
		}
	} else if runtime.GOOS == "windows" {
		cmds := [][]string{
			{"netsh", "interface", "ipv4", "set", "address", "name=" + dev, "static", "198.18.0.1", "255.255.255.255"},
			{"netsh", "interface", "ipv4", "add", "route", "0.0.0.0", "0.0.0.0", dev},
			{"netsh", "interface", "ipv4", "set", "dns", "name=" + dev, "static", "1.1.1.1"},
		}
		for _, c := range cmds {
			o, err := exec.Command(c[0], c[1:]...).CombinedOutput()
			msg := strings.TrimSpace(string(o))
			out.WriteString(msg + "\n")
			if err != nil {
				return out.String(), fmt.Errorf("%s: %w (admin rights required)", strings.Join(c, " "), err)
			}
		}
	} else {
		return "", fmt.Errorf("network setup is automated for linux and windows only")
	}
	return out.String(), nil
}

func (a *App) UndoNetworkSetup() (string, error) {
	a.mu.Lock()
	dev := a.device
	if dev == "" {
		dev = "celltun"
	}
	a.mu.Unlock()

	var out strings.Builder
	if runtime.GOOS == "linux" {
		if !a.isRoot() {
			return "", fmt.Errorf("this needs root: launch the app with sudo, or undo manually")
		}
		cmds := [][]string{
			{"ip", "route", "del", "default", "dev", dev},
			{"ip", "addr", "del", "198.18.0.1/32", "dev", dev},
		}
		for _, c := range cmds {
			o, _ := exec.Command(c[0], c[1:]...).CombinedOutput()
			if msg := strings.TrimSpace(string(o)); msg != "" {
				out.WriteString(msg + "\n")
			}
		}
	} else if runtime.GOOS == "windows" {
		cmds := [][]string{
			{"netsh", "interface", "ipv4", "delete", "route", "0.0.0.0", "0.0.0.0", dev},
			{"netsh", "interface", "ipv4", "set", "address", "name=" + dev, "dhcp"},
			{"netsh", "interface", "ipv4", "set", "dns", "name=" + dev, "dhcp"},
		}
		for _, c := range cmds {
			o, _ := exec.Command(c[0], c[1:]...).CombinedOutput()
			if msg := strings.TrimSpace(string(o)); msg != "" {
				out.WriteString(msg + "\n")
			}
		}
	}
	return out.String(), nil
}
