package main

import (
	"encoding/hex"
	"path/filepath"
	"testing"

	gesture "phonepage/windows/gesture/zz_gesture"
)

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
