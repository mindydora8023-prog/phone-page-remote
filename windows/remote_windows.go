package main

import (
	"crypto/hmac"
	"crypto/rand"
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

// settings is what lands on disk: the UI preferences plus the last pairing key
// that worked. The key is kept out of preferences so State() never ships it to
// the page, and it is separate from the snapshot so a screenshot cannot leak it.
type settings struct {
	preferences
	Code string `json:"code,omitempty"`
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
	// Remembered tells the page it can connect without asking for the key again.
	Remembered bool `json:"remembered"`
	// Pairing is true while the phone is being asked; PairCode is the six digits
	// the person has to see on both screens.
	Pairing  bool   `json:"pairing"`
	PairCode string `json:"pairCode,omitempty"`
}

type Remote struct {
	mu     sync.Mutex
	keysMu sync.Mutex
	state  snapshot
	ble    *bleWorker
	file   string
	secret [16]byte
	hasKey bool
}

func newRemote(file string) *Remote {
	prefs := preferences{Mode: "reading", Prev: "F9", Next: "F10"}
	var secret [16]byte
	var hasKey bool
	upgrade := false
	data, err := os.ReadFile(file)
	if err == nil {
		var loaded settings
		if json.Unmarshal(data, &loaded) == nil {
			if gesture.Command(loaded.Mode, "next") != 0 && validKey(loaded.Prev) &&
				validKey(loaded.Next) && loaded.Prev != loaded.Next {
				prefs = loaded.preferences
			}
			if key, ok := decodeStoredCode(loaded.Code); ok {
				secret, hasKey = key, true
				// A plain-text key from an older version is rewritten sealed on save.
				upgrade = legacyPlainCode(loaded.Code)
			}
		}
	}
	remote := &Remote{file: file, ble: newBLEWorker(),
		state: snapshot{Settings: prefs, Phones: []phone{}}, secret: secret, hasKey: hasKey}
	remote.state.Remembered = hasKey
	if upgrade {
		if err := remote.writeFile(prefs, secret, hasKey); err != nil {
			remote.state.Problem = true
		}
	}
	return remote
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
	secret, remembered, err := r.secretFor(code)
	if err != nil {
		return err
	}
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
	clear(secret[:])
	r.mu.Lock()
	defer r.mu.Unlock()
	r.state.Phase = ""
	if errors.Is(err, errPhoneRejected) {
		if remembered {
			// The phone's key was rotated, or this is a different phone. Drop the
			// stored key so the next attempt asks for the current one.
			r.forgetSecretLocked()
			r.setMessage("记住的密钥已失效。请在手机上核对配对密钥后重新输入。", true)
		} else {
			r.setMessage("验证失败。请核对连接码，并确认接收端和无障碍服务已打开。", true)
		}
	} else if err != nil {
		r.setMessage("连接失败："+err.Error()+"。请重新扫描手机。", true)
	} else {
		r.state.Device = target.Name
		r.rememberSecretLocked(secret)
		r.setMessage("手机已连接。打开小说或短视频，即可试发翻页。", false)
	}
	return nil
}

// Pair invents the key for this computer, asks the phone to show a matching six
// digit code, and connects once the person holding the phone allows it.
func (r *Remote) Pair(id string) error {
	r.mu.Lock()
	if r.state.Phase != "" || r.state.Device != "" {
		r.mu.Unlock()
		return errors.New("请先断开手机再配对")
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
	var secret [16]byte
	if _, err := rand.Read(secret[:]); err != nil {
		r.mu.Unlock()
		return err
	}
	r.state.Phase = "pairing"
	r.state.Pairing = true
	r.state.PairCode = pairingCode(secret)
	r.setMessage("请在手机上核对 6 位数字后点「允许」。", false)
	r.mu.Unlock()

	var result byte
	// Whatever the radio does, the UI must not stay in "pairing" forever.
	finished := make(chan struct{})
	go func() {
		select {
		case <-finished:
		case <-time.After(90 * time.Second):
			r.mu.Lock()
			if r.state.Pairing {
				r.state.Pairing, r.state.Phase, r.state.PairCode = false, "", ""
				r.setMessage("配对超时。请在手机上核对数字后重试。", true)
			}
			r.mu.Unlock()
		}
	}()
	pairErr := r.ble.run(func() error {
		var err error
		result, err = pairPhone(target, secret)
		return err
	})
	close(finished)

	r.mu.Lock()
	r.state.Phase = ""
	r.state.Pairing = false
	r.state.PairCode = ""
	switch {
	case errors.Is(pairErr, errNoPairing):
		r.setMessage("这台手机的接收端版本较旧。请升级手机端，或直接输入 32 位连接码。", true)
	case pairErr != nil:
		r.setMessage("配对失败："+pairErr.Error()+"。请重新扫描手机。", true)
	case result == pairDenied:
		r.setMessage("手机拒绝了配对。可以重试，或直接输入连接码。", true)
	case result == pairTimeout:
		r.setMessage("配对超时。请在手机上点「允许」后重试。", true)
	case result == pairAllowed:
		r.rememberSecretLocked(secret)
		r.setMessage("已与手机配对，正在连接…", false)
	default:
		r.setMessage("配对没有得到结果，请重试。", true)
	}
	clear(secret[:])
	paired := result == pairAllowed && pairErr == nil
	r.mu.Unlock()
	if !paired {
		return nil
	}
	return r.Connect(id, "")
}

// pairingCode is the six digits both screens show. PairRequest.code on the phone
// computes the same value; the Go test pins it against a known key.
func pairingCode(secret [16]byte) string {
	mac := hmac.New(sha256.New, secret[:])
	_, _ = mac.Write([]byte("ppr-pair-code"))
	tag := mac.Sum(nil)
	value := uint32(tag[0])<<24 | uint32(tag[1])<<16 | uint32(tag[2])<<8 | uint32(tag[3])
	return fmt.Sprintf("%06d", value%1_000_000)
}

// secretFor resolves the key to use: the typed one, or the remembered one when
// the field was left empty.
func (r *Remote) secretFor(code string) ([16]byte, bool, error) {
	trimmed := strings.TrimSpace(code)
	if trimmed == "" {
		// Read the stored key lazily: encode() would need locking, and this path
		// matters more than the allocation.
		if stored, ok := r.storedSecret(); ok {
			return stored, true, nil
		}
		return [16]byte{}, false, errors.New("请输入手机上显示的 32 位十六进制连接码")
	}
	decoded, err := hex.DecodeString(trimmed)
	if err != nil || len(decoded) != 16 {
		return [16]byte{}, false, errors.New("请输入手机上显示的 32 位十六进制连接码")
	}
	var secret [16]byte
	copy(secret[:], decoded)
	clear(decoded)
	return secret, false, nil
}

func (r *Remote) storedSecret() ([16]byte, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.hasKey {
		return [16]byte{}, false
	}
	return r.secret, true
}

func (r *Remote) ForgetSecret() error {
	r.keysMu.Lock()
	defer r.keysMu.Unlock()
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.hasKey {
		return nil
	}
	r.forgetSecretLocked()
	r.setMessage("已忘记这台手机的密钥。下次连接需要重新输入。", false)
	return r.savePreferences()
}

// rememberSecretLocked stores the key that just authenticated, so the next
// connection only needs a click.
func (r *Remote) rememberSecretLocked(secret [16]byte) {
	r.secret, r.hasKey = secret, true
	r.state.Remembered = true
	if err := r.savePreferences(); err != nil {
		r.setMessage("设置保存失败，下次连接可能需要重新输入密钥。", true)
	}
}

func (r *Remote) forgetSecretLocked() {
	var zero [16]byte
	r.secret, r.hasKey = zero, false
	r.state.Remembered = false
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
		link := r.ble.link
		err := link.send(byte(command))
		if err == nil || errors.Is(err, errPhoneRejected) {
			return err
		}
		// The link is gone. Reconnect with the same key before giving up, so a
		// radio hiccup does not cost the whole session.
		target, secret := link.target, link.secret
		link.close()
		r.ble.link = nil
		r.mu.Lock()
		r.setMessage("蓝牙连接中断，正在自动重连…", false)
		r.mu.Unlock()
		fresh, openErr := r.reconnect(target, secret)
		clear(secret[:])
		if openErr != nil {
			return err
		}
		r.ble.link = fresh
		var notSent unsentError
		if !errors.As(err, &notSent) {
			// The command may have reached the phone; sending it again could
			// turn two pages.
			return errResultUnknown
		}
		err = fresh.send(byte(command))
		if err != nil && !errors.Is(err, errPhoneRejected) {
			fresh.close()
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
	} else if errors.Is(err, errResultUnknown) {
		r.setMessage("连接曾中断，已自动重连。刚才那一下可能没有送达，请看手机确认。", true)
	} else if errors.Is(err, errGestureOff) {
		r.setMessage("连接正常，但手机的无障碍服务未开启。请在手机「手机翻页遥控」里打开无障碍设置并启用。", true)
	} else if errors.Is(err, errPhoneRejected) {
		r.setMessage("手机未接受手势。请确认屏幕已解锁、无障碍服务已开启，或稍后再按。", true)
	} else {
		r.state.Device = ""
		r.setMessage("蓝牙连接已断开，手势结果未知。请重新扫描连接。", true)
	}
	lost := err != nil && !errors.Is(err, errPhoneRejected) && !errors.Is(err, errResultUnknown)
	r.mu.Unlock()
	if lost {
		_ = r.SetGlobal(false)
	}
	return nil
}

// errResultUnknown: the link dropped after the command was written, and the
// reconnect worked. The page may or may not have turned.
var errResultUnknown = errors.New("翻页结果未知")

// reconnect runs on the BLE worker. It tries the address the phone was found
// at, then, because Android rotates its advertising address, a short scan for
// the receiver service. A wrong phone found by the scan simply fails to
// authenticate.
func (r *Remote) reconnect(target phone, secret [16]byte) (*bleLink, error) {
	link, err := openBLE(target, secret)
	if err == nil {
		return link, nil
	}
	phones, scanErr := scanPhones(3 * time.Second)
	if scanErr != nil || len(phones) != 1 || phones[0].ID == target.ID {
		return nil, err
	}
	link, err = openBLE(phones[0], secret)
	if err != nil {
		return nil, err
	}
	r.mu.Lock()
	r.state.Phones = phones
	r.mu.Unlock()
	return link, nil
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

// writeFile persists preferences plus the remembered pairing key. It takes no
// locks: callers either hold r.mu or pass a snapshot of it.
func (r *Remote) writeFile(prefs preferences, secret [16]byte, hasKey bool) error {
	next := settings{preferences: prefs}
	if hasKey {
		sealed, err := protectSecret(secret[:])
		if err != nil {
			return fmt.Errorf("保存设置失败：%w", err)
		}
		next.Code = sealed
	}
	data, err := json.MarshalIndent(next, "", "  ")
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
	return nil
}

// save writes new preferences and keeps the remembered key as it is.
func (r *Remote) save(next preferences) error {
	r.mu.Lock()
	secret, hasKey := r.secret, r.hasKey
	r.mu.Unlock()
	if err := r.writeFile(next, secret, hasKey); err != nil {
		return err
	}
	r.mu.Lock()
	r.state.Settings = next
	r.mu.Unlock()
	return nil
}

// savePreferences rewrites the file from current state; the caller holds r.mu.
func (r *Remote) savePreferences() error {
	return r.writeFile(r.state.Settings, r.secret, r.hasKey)
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
