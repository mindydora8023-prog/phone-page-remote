package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/egoist/mygo"
	gesture "phonepage/windows/gesture/zz_gesture"
)

type preferences struct {
	Mode string `json:"mode"`
	Prev string `json:"prev"`
	Next string `json:"next"`
}

type snapshot struct {
	Settings preferences `json:"settings"`
	Phones   []phone     `json:"phones"`
	Phase    string      `json:"phase"`
	Device   string      `json:"device"`
	Global   bool        `json:"global"`
	Message  string      `json:"message"`
	Problem  bool        `json:"problem"`
	Last     string      `json:"last"`
	Sent     uint64      `json:"sent"`
}

type Remote struct {
	mu     sync.Mutex
	keysMu sync.Mutex
	state  snapshot
	ble    *bleWorker
	file   string
}

func newRemote(file string) *Remote {
	settings := preferences{Mode: "reading", Prev: "F9", Next: "F10"}
	data, err := os.ReadFile(file)
	if err == nil {
		var saved preferences
		if json.Unmarshal(data, &saved) == nil && gesture.Command(saved.Mode, "next") != 0 &&
			validKey(saved.Prev) && validKey(saved.Next) && saved.Prev != saved.Next {
			settings = saved
		}
	}
	return &Remote{file: file, ble: newBLEWorker(), state: snapshot{Settings: settings, Phones: []phone{}}}
}

func (r *Remote) State() snapshot {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.state
}

func (r *Remote) Scan() error {
	r.mu.Lock()
	if r.state.Phase != "" || r.state.Device != "" {
		r.mu.Unlock()
		return errors.New("请先完成当前操作或断开手机")
	}
	r.state.Phase = "scanning"
	r.state.Phones = []phone{}
	r.setMessage("正在扫描附近的翻页接收端…", false)
	r.mu.Unlock()
	var phones []phone
	err := r.ble.run(func() error {
		var err error
		phones, err = scanPhones(5 * time.Second)
		return err
	})
	r.mu.Lock()
	defer r.mu.Unlock()
	r.state.Phase = ""
	if err != nil {
		r.setMessage("扫描失败："+err.Error(), true)
	} else {
		r.state.Phones = phones
		if len(phones) == 0 {
			r.setMessage("未找到接收端。请在手机上开启蓝牙接收，再扫描一次。", false)
		} else {
			r.setMessage("请选择接收端，并输入手机上显示的连接码。", false)
		}
	}
	return nil
}

func (r *Remote) Connect(id, code string) error {
	decoded, err := hex.DecodeString(strings.TrimSpace(code))
	if err != nil || len(decoded) != 16 {
		return errors.New("请输入手机上显示的 32 位十六进制连接码")
	}
	secret := [16]byte(decoded)
	clear(decoded)
	defer clear(secret[:])
	r.mu.Lock()
	if r.state.Phase != "" || r.state.Device != "" {
		r.mu.Unlock()
		return errors.New("请先完成当前操作或断开手机")
	}
	var target phone
	for _, item := range r.state.Phones {
		if item.ID == id {
			target = item
			break
		}
	}
	if target.ID == "" {
		r.mu.Unlock()
		return errors.New("请先扫描并选择手机")
	}
	r.state.Phase = "connecting"
	r.setMessage("正在连接并验证手机…", false)
	r.mu.Unlock()
	err = r.ble.run(func() error {
		link, err := openBLE(target, secret)
		if err == nil {
			r.ble.link = link
		}
		return err
	})
	r.mu.Lock()
	defer r.mu.Unlock()
	r.state.Phase = ""
	if errors.Is(err, errPhoneRejected) {
		r.setMessage("验证失败。请核对连接码，并确认接收端和无障碍服务已打开。", true)
	} else if err != nil {
		r.setMessage("连接失败："+err.Error()+"。请重新扫描手机。", true)
	} else {
		r.state.Device = target.Name
		r.setMessage("手机已连接。打开小说或短视频，即可试发翻页。", false)
	}
	return nil
}

func (r *Remote) Disconnect() error {
	r.mu.Lock()
	if r.state.Phase != "" || r.state.Device == "" {
		r.mu.Unlock()
		return errors.New("当前没有可断开的空闲连接")
	}
	r.state.Phase = "disconnecting"
	r.state.Device = ""
	r.mu.Unlock()
	_ = r.SetGlobal(false)
	err := r.ble.run(func() error {
		r.ble.link.close()
		r.ble.link = nil
		return nil
	})
	r.mu.Lock()
	defer r.mu.Unlock()
	r.state.Phase = ""
	r.setMessage("已断开。重新扫描可连接手机。", false)
	return err
}

func (r *Remote) Send(action string) error {
	if action != "next" && action != "prev" {
		return errors.New("未知翻页动作")
	}
	r.mu.Lock()
	if r.state.Device == "" {
		r.mu.Unlock()
		return errors.New("请先连接手机")
	}
	if r.state.Phase != "" {
		r.mu.Unlock()
		return errors.New("上一条操作正在进行")
	}
	command := gesture.Command(r.state.Settings.Mode, action)
	r.state.Phase = "sending"
	r.mu.Unlock()
	err := r.ble.run(func() error {
		err := r.ble.link.send(byte(command))
		if err != nil && !errors.Is(err, errPhoneRejected) {
			r.ble.link.close()
			r.ble.link = nil
		}
		return err
	})
	r.mu.Lock()
	r.state.Phase = ""
	if err == nil {
		r.state.Last = action
		r.state.Sent++
		r.setMessage("手机已接收翻页指令。", false)
	} else if errors.Is(err, errPhoneRejected) {
		r.setMessage("手机未接受手势。请确认屏幕已解锁、无障碍服务已开启，或稍后再按。", true)
	} else {
		r.state.Device = ""
		r.setMessage("蓝牙连接已断开，手势结果未知。请重新扫描连接。", true)
	}
	lost := err != nil && !errors.Is(err, errPhoneRejected)
	r.mu.Unlock()
	if lost {
		_ = r.SetGlobal(false)
	}
	return nil
}

func (r *Remote) SetMode(mode string) error {
	if gesture.Command(mode, "next") == 0 {
		return errors.New("未知手势模式")
	}
	r.keysMu.Lock()
	defer r.keysMu.Unlock()
	settings := r.State().Settings
	settings.Mode = mode
	return r.save(settings)
}

func (r *Remote) SetKey(action, shortcut string) error {
	if !validKey(shortcut) {
		return errors.New("该键不适用。请尝试方向键、翻页键、F1–F24（F12 除外），或带 Ctrl、Alt、Shift、Win 的字母数字组合。")
	}
	r.keysMu.Lock()
	defer r.keysMu.Unlock()
	state := r.State()
	settings := state.Settings
	var previous string
	switch action {
	case "prev":
		previous, settings.Prev = settings.Prev, shortcut
	case "next":
		previous, settings.Next = settings.Next, shortcut
	default:
		return errors.New("未知翻页动作")
	}
	if settings.Prev == settings.Next {
		return errors.New("上一页和下一页需要使用不同的按键")
	}
	if previous == shortcut {
		return nil
	}
	if state.Global {
		if err := r.register(action, shortcut); err != nil {
			return err
		}
	}
	if err := r.save(settings); err != nil {
		if state.Global {
			mygo.GlobalShortcut.Unregister(shortcut)
		}
		return err
	}
	if state.Global {
		mygo.GlobalShortcut.Unregister(previous)
	}
	return nil
}

func (r *Remote) SetGlobal(on bool) error {
	r.keysMu.Lock()
	defer r.keysMu.Unlock()
	state := r.State()
	if on == state.Global {
		return nil
	}
	if on {
		if state.Device == "" {
			return errors.New("请先连接手机")
		}
		if err := r.register("prev", state.Settings.Prev); err != nil {
			return err
		}
		if err := r.register("next", state.Settings.Next); err != nil {
			mygo.GlobalShortcut.Unregister(state.Settings.Prev)
			return err
		}
	} else {
		mygo.GlobalShortcut.Unregister(state.Settings.Prev)
		mygo.GlobalShortcut.Unregister(state.Settings.Next)
	}
	r.mu.Lock()
	r.state.Global = on
	r.mu.Unlock()
	return nil
}

func (r *Remote) register(action, shortcut string) error {
	if err := mygo.GlobalShortcut.Register(shortcut, func() { go func() { _ = r.Send(action) }() }); err != nil {
		return fmt.Errorf("快捷键 %s 注册失败，可能已被 Windows 或其他应用占用", shortcut)
	}
	return nil
}

func (r *Remote) setMessage(message string, problem bool) {
	r.state.Message, r.state.Problem = message, problem
}

func (r *Remote) save(settings preferences) error {
	data, err := json.MarshalIndent(settings, "", "  ")
	if err != nil {
		return err
	}
	dir := filepath.Dir(r.file)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return fmt.Errorf("保存设置失败：%w", err)
	}
	f, err := os.CreateTemp(dir, "settings-*.json")
	if err != nil {
		return fmt.Errorf("保存设置失败：%w", err)
	}
	defer os.Remove(f.Name())
	_, writeErr := f.Write(data)
	if err := errors.Join(writeErr, f.Close()); err != nil {
		return fmt.Errorf("保存设置失败：%w", err)
	}
	if err := os.Rename(f.Name(), r.file); err != nil {
		return fmt.Errorf("保存设置失败：%w", err)
	}
	r.mu.Lock()
	r.state.Settings = settings
	r.mu.Unlock()
	return nil
}

var shortcutPattern = regexp.MustCompile(`^(Ctrl\+)?(Alt\+)?(Shift\+)?(Super\+)?(F([1-9]|1[0-9]|2[0-4])|Up|Down|Left|Right|PageUp|PageDown|Home|End|[A-Z0-9]|Space|Enter|Tab|Backspace|Delete|Insert|Escape)$`)

func validKey(key string) bool {
	parts := shortcutPattern.FindStringSubmatch(key)
	if parts == nil || parts[5] == "F12" {
		return false
	}
	if strings.Contains(key, "+") {
		return true
	}
	switch parts[5] {
	case "Up", "Down", "Left", "Right", "PageUp", "PageDown", "Home", "End":
		return true
	}
	return parts[6] != ""
}

func signedCommand(secret, challenge [16]byte, command byte) [17]byte {
	var packet [17]byte
	packet[0] = command
	mac := hmac.New(sha256.New, secret[:])
	_, _ = mac.Write(challenge[:])
	_, _ = mac.Write(packet[:1])
	copy(packet[1:], mac.Sum(nil)[:16])
	return packet
}
