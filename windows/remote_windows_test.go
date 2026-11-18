package main

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	gesture "phonepage/windows/gesture/zz_gesture"
)

// pairingKey is a deterministic 16-byte key used by the tests below.
func pairingKey() [16]byte {
	var secret [16]byte
	for i := range secret {
		secret[i] = byte(i + 1)
	}
	return secret
}

func TestFreshInstallAsksForAKey(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	r := newRemote(path)
	t.Cleanup(func() { close(r.ble.jobs) })
	if r.State().Remembered {
		t.Fatal("a fresh install already claims a remembered key")
	}
	if _, _, err := r.secretFor(""); err == nil {
		t.Fatal("an empty code was accepted without a remembered key")
	}
}

func TestRememberedKeySurvivesRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	r := newRemote(path)
	t.Cleanup(func() { close(r.ble.jobs) })
	want := pairingKey()
	r.mu.Lock()
	r.rememberSecretLocked(want)
	r.mu.Unlock()
	if !r.State().Remembered {
		t.Fatal("remembering a key did not update the snapshot")
	}

	restored := newRemote(path)
	t.Cleanup(func() { close(restored.ble.jobs) })
	if !restored.State().Remembered {
		t.Fatal("the remembered key was not reloaded from disk")
	}
	got, remembered, err := restored.secretFor("")
	if err != nil || !remembered || got != want {
		t.Fatalf("stored key came back as %x remembered=%v err=%v", got, remembered, err)
	}

	// The key must never reach the page: State() is polled every 400ms and the
	// snapshot is what the renderer sees.
	if leaked := hex.EncodeToString(want[:]); strings.Contains(fmt.Sprintf("%+v", restored.State()), leaked) {
		t.Fatal("the snapshot leaks the pairing key")
	}
}

func TestForgetKeyClearsTheFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	r := newRemote(path)
	t.Cleanup(func() { close(r.ble.jobs) })
	r.mu.Lock()
	r.rememberSecretLocked(pairingKey())
	r.mu.Unlock()
	if err := r.ForgetSecret(); err != nil {
		t.Fatal(err)
	}
	if r.State().Remembered {
		t.Fatal("forget left the key in memory")
	}
	restored := newRemote(path)
	t.Cleanup(func() { close(restored.ble.jobs) })
	if restored.State().Remembered {
		t.Fatal("forget left the key on disk")
	}
	if _, _, err := restored.secretFor(""); err == nil {
		t.Fatal("an empty code was accepted after forgetting")
	}
}

func TestConnectUsesTheRememberedKey(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	r := newRemote(path)
	t.Cleanup(func() { close(r.ble.jobs) })
	r.mu.Lock()
	r.rememberSecretLocked(pairingKey())
	r.mu.Unlock()
	// Nothing was scanned, so the call must stop at device selection. Reaching that
	// error proves the empty code was resolved from the stored key instead of being
	// rejected up front.
	err := r.Connect("no-such-device", "")
	if err == nil || !strings.Contains(err.Error(), "请先扫描") {
		t.Fatalf("Connect with a remembered key returned %v", err)
	}
}

func TestMalformedCodeIsRejected(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	r := newRemote(path)
	t.Cleanup(func() { close(r.ble.jobs) })
	for _, code := range []string{"zz", "0011", strings.Repeat("g", 32), strings.Repeat("ab", 15)} {
		if _, _, err := r.secretFor(code); err == nil {
			t.Errorf("%q was accepted", code)
		}
	}
	if _, remembered, err := r.secretFor(strings.Repeat("ab", 16)); err != nil || remembered {
		t.Errorf("a typed 32-hex key was refused: %v remembered=%v", err, remembered)
	}
}

func TestPairingCodeMatchesAndroid(t *testing.T) {
	// PairRequestTest pins the same value for the same key on the phone side. If
	// either derivation drifts, one of the two builds fails.
	if got := pairingCode(pairingKey()); got != "702144" {
		t.Fatalf("pairing code = %q, want 702144", got)
	}
	zero := pairingCode([16]byte{})
	if len(zero) != 6 {
		t.Fatalf("pairing code %q is not six digits", zero)
	}
	if pairingCode(pairingKey()) == zero {
		t.Fatal("different keys produced the same code")
	}
}

func TestPairRequestFrame(t *testing.T) {
	key := pairingKey()
	wire := pairRequest(key)
	if len(wire) != 17 || wire[0] != 1 {
		t.Fatalf("frame = %x, want 17 bytes starting with version 1", wire)
	}
	if !bytes.Equal(wire[1:], key[:]) {
		t.Fatal("frame does not carry the key")
	}
}

func TestSealedKeyRoundTrips(t *testing.T) {
	want := pairingKey()
	sealed, err := protectSecret(want[:])
	if err != nil {
		t.Skipf("DPAPI unavailable in this environment: %v", err)
	}
	if sealed == hex.EncodeToString(want[:]) {
		t.Fatal("the key was stored as plain text")
	}
	if strings.Contains(sealed, hex.EncodeToString(want[:])) {
		t.Fatal("the sealed value still contains the key")
	}
	got, err := unprotectSecret(sealed)
	if err != nil {
		t.Fatal(err)
	}
	if [16]byte(got) != want {
		t.Fatalf("round trip produced %x, want %x", got, want)
	}
	if _, err := unprotectSecret(hex.EncodeToString(want[:])); err == nil {
		t.Fatal("DPAPI opened a plain hex string")
	}
}

func TestPlainKeyFromOlderVersionIsSealedOnLoad(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	want := pairingKey()
	legacy := fmt.Sprintf(`{"mode":"reading","prev":"PageUp","next":"PageDown","code":%q}`,
		hex.EncodeToString(want[:]))
	if err := os.WriteFile(path, []byte(legacy), 0600); err != nil {
		t.Fatal(err)
	}
	r := newRemote(path)
	t.Cleanup(func() { close(r.ble.jobs) })
	if !r.State().Remembered {
		t.Fatal("the plain key from v1.1.0 was not picked up")
	}
	got, remembered, err := r.secretFor("")
	if err != nil || !remembered || got != want {
		t.Fatalf("seeded key came back as %x remembered=%v err=%v", got, remembered, err)
	}

	// newRemote rewrites the file, so what is on disk must already be sealed.
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), hex.EncodeToString(want[:])) {
		t.Fatal("the plain-text key survived the upgrade")
	}
	var stored settings
	if err := json.Unmarshal(data, &stored); err != nil {
		t.Fatal(err)
	}
	if _, ok := decodeStoredCode(stored.Code); !ok {
		t.Fatalf("stored code no longer decodes: %q", stored.Code)
	}
}

func TestCorruptStoredKeyIsIgnored(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	if err := os.WriteFile(path, []byte(`{"mode":"video","prev":"PageUp","next":"PageDown","code":"not-a-key"}`), 0600); err != nil {
		t.Fatal(err)
	}
	r := newRemote(path)
	t.Cleanup(func() { close(r.ble.jobs) })
	if r.State().Remembered {
		t.Fatal("a corrupt key was treated as remembered")
	}
	if got := r.State().Settings.Mode; got != "video" {
		t.Errorf("preferences were lost with the key: mode=%q", got)
	}
}

func TestGestureModes(t *testing.T) {
	for mode, want := range map[string][2]int{
		"reading": {2, 1}, "reading-rtl": {1, 2}, "video": {4, 3}, "video-reversed": {3, 4},
	} {
		got := [2]int{gesture.Command(mode, "prev"), gesture.Command(mode, "next")}
		if got != want {
			t.Errorf("%s produced %v, want %v", mode, got, want)
		}
	}
}

func TestCommandMatchesAndroidProtocol(t *testing.T) {
	var key, challenge [16]byte
	for i := range key {
		key[i], challenge[i] = byte(i), byte(i+16)
	}
	packet := signedCommand(key, challenge, 3)
	if got := hex.EncodeToString(packet[:]); got != "03d2b5cca4bce827e635239a0e5b89a988" {
		t.Fatalf("unexpected packet %s", got)
	}
}

func TestSettingsPersistAndRejectDuplicate(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	r := newRemote(path)
	t.Cleanup(func() { close(r.ble.jobs) })
	if err := r.SetKey("prev", "F10"); err == nil {
		t.Fatal("duplicate shortcut was accepted")
	}
	if err := r.SetKey("prev", "Ctrl+Shift+K"); err != nil {
		t.Fatal(err)
	}
	if err := r.SetMode("video"); err != nil {
		t.Fatal(err)
	}
	restored := newRemote(path)
	t.Cleanup(func() { close(restored.ble.jobs) })
	want := preferences{Mode: "video", Prev: "Ctrl+Shift+K", Next: "F10"}
	if got := restored.State().Settings; got != want {
		t.Errorf("loaded %+v, want %+v", got, want)
	}
}
