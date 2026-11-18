package main

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"unsafe"

	"syscall"
)

// The pairing key used to live in settings.json as plain text. It is now sealed
// with DPAPI (CryptProtectData), which ties the ciphertext to the current Windows
// user: copying the file to another account yields data that cannot be opened,
// and nothing readable sits on disk.
//
// No new module is pulled in: crypt32 is called through syscall directly.

var (
	crypt32              = syscall.NewLazyDLL("crypt32.dll")
	kernel32             = syscall.NewLazyDLL("kernel32.dll")
	procCryptProtectData = crypt32.NewProc("CryptProtectData")
	procCryptUnprotect   = crypt32.NewProc("CryptUnprotectData")
	procLocalFree        = kernel32.NewProc("LocalFree")
)

// cryptProtectUIForbidden keeps DPAPI from ever showing a prompt.
const cryptProtectUIForbidden = 0x1

type dataBlob struct {
	size uint32
	data *byte
}

func blobOf(buf []byte) dataBlob {
	if len(buf) == 0 {
		return dataBlob{}
	}
	return dataBlob{size: uint32(len(buf)), data: &buf[0]}
}

func (b dataBlob) bytes() []byte {
	if b.size == 0 || b.data == nil {
		return nil
	}
	return unsafe.Slice(b.data, b.size)
}

// protectSecret seals the 16-byte key for this Windows user and returns it as
// base64, which is what settings.json stores.
func protectSecret(secret []byte) (string, error) {
	if len(secret) == 0 {
		return "", errors.New("empty secret")
	}
	in := blobOf(secret)
	var out dataBlob
	ok, _, err := procCryptProtectData.Call(
		uintptr(unsafe.Pointer(&in)), 0, 0, 0, 0,
		uintptr(cryptProtectUIForbidden), uintptr(unsafe.Pointer(&out)))
	if ok == 0 {
		return "", fmt.Errorf("CryptProtectData: %w", err)
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.data)))
	return base64.StdEncoding.EncodeToString(out.bytes()), nil
}

// unprotectSecret opens what protectSecret wrote. It fails for another user, for
// a corrupt blob, and for the plain hex older versions stored.
func unprotectSecret(encoded string) ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, err
	}
	if len(raw) == 0 {
		return nil, errors.New("empty blob")
	}
	in := blobOf(raw)
	var out dataBlob
	ok, _, err := procCryptUnprotect.Call(
		uintptr(unsafe.Pointer(&in)), 0, 0, 0, 0,
		uintptr(cryptProtectUIForbidden), uintptr(unsafe.Pointer(&out)))
	if ok == 0 {
		return nil, fmt.Errorf("CryptUnprotectData: %w", err)
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.data)))
	secret := make([]byte, out.size)
	copy(secret, out.bytes())
	return secret, nil
}

// decodeStoredCode accepts the sealed form and the plain hex that versions up to
// v1.1.0 wrote, so upgrading does not cost the user a re-pair.
func decodeStoredCode(code string) ([16]byte, bool) {
	var key [16]byte
	if code == "" {
		return key, false
	}
	if opened, err := unprotectSecret(code); err == nil {
		if len(opened) == 16 {
			copy(key[:], opened)
			clear(opened)
			return key, true
		}
		clear(opened)
	}
	if decoded, err := hex.DecodeString(code); err == nil && len(decoded) == 16 {
		copy(key[:], decoded)
		clear(decoded)
		return key, true
	}
	return key, false
}

// legacyPlainCode reports whether the stored value is still the unsealed hex form,
// which the next save upgrades.
func legacyPlainCode(code string) bool {
	if code == "" {
		return false
	}
	if _, err := unprotectSecret(code); err == nil {
		return false
	}
	decoded, err := hex.DecodeString(code)
	return err == nil && len(decoded) == 16
}
