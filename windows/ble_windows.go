package main

import (
	"errors"
	"fmt"
	"runtime"
	"sort"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/go-ole/go-ole"
	"github.com/saltosystems/winrt-go"
	"github.com/saltosystems/winrt-go/windows/devices/bluetooth"
	"github.com/saltosystems/winrt-go/windows/devices/bluetooth/advertisement"
	gatt "github.com/saltosystems/winrt-go/windows/devices/bluetooth/genericattributeprofile"
	"github.com/saltosystems/winrt-go/windows/foundation"
	"github.com/saltosystems/winrt-go/windows/storage/streams"
)

const serviceID = "{0054E118-E465-4B7F-85E7-1B0963092301}"
const challengeID = "{0054E118-E465-4B7F-85E7-1B0963092302}"
const commandID = "{0054E118-E465-4B7F-85E7-1B0963092303}"

var errPhoneRejected = errors.New("手机拒绝了此操作")

type phone struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	address uint64
	kind    bluetooth.BluetoothAddressType
}

type bleLink struct {
	device    *bluetooth.BluetoothLEDevice
	service   *gatt.GattDeviceService
	challenge *gatt.GattCharacteristic
	command   *gatt.GattCharacteristic
	secret    [16]byte
}

type bleWorker struct {
	jobs chan func(error)
	link *bleLink
}

func newBLEWorker() *bleWorker {
	worker := &bleWorker{jobs: make(chan func(error))}
	go func() {
		runtime.LockOSThread()
		initErr := ole.RoInitialize(1)
		for job := range worker.jobs {
			job(initErr)
		}
	}()
	return worker
}

func (w *bleWorker) run(operation func() error) error {
	result := make(chan error, 1)
	w.jobs <- func(initErr error) {
		if initErr != nil {
			result <- fmt.Errorf("Windows 蓝牙初始化失败：%w", initErr)
			return
		}
		result <- operation()
	}
	return <-result
}

func scanPhones(duration time.Duration) ([]phone, error) {
	watcher, err := advertisement.NewBluetoothLEAdvertisementWatcher()
	if err != nil {
		return nil, err
	}
	defer watcher.Release()
	if err := watcher.SetScanningMode(advertisement.BluetoothLEScanningModeActive); err != nil {
		return nil, err
	}

	var mu sync.Mutex
	found := make(map[string]phone)
	guid := winrt.ParameterizedInstanceGUID(foundation.GUIDTypedEventHandler,
		advertisement.SignatureBluetoothLEAdvertisementWatcher,
		advertisement.SignatureBluetoothLEAdvertisementReceivedEventArgs)
	received := foundation.NewTypedEventHandler(ole.NewGUID(guid), func(_ *foundation.TypedEventHandler, _, ptr unsafe.Pointer) {
		args := (*advertisement.BluetoothLEAdvertisementReceivedEventArgs)(ptr)
		adv, err := args.GetAdvertisement()
		if err != nil || adv == nil {
			return
		}
		defer adv.Release()
		address, err := args.GetBluetoothAddress()
		if err != nil {
			return
		}
		kind := advertisementAddressType(args)
		id := fmt.Sprintf("%012X-%d", address, kind)
		name, _ := adv.GetLocalName()
		isReceiver := advertisesService(adv)
		mu.Lock()
		defer mu.Unlock()
		item, known := found[id]
		if !known && (!isReceiver || len(found) >= 32) {
			return
		}
		if !known {
			item = phone{ID: id, address: address, kind: kind}
		}
		if name != "" {
			item.Name = name
		}
		found[id] = item
	})
	defer received.Release()
	token, err := watcher.AddReceived(received)
	if err != nil {
		return nil, err
	}
	defer watcher.RemoveReceived(token)
	if err := watcher.Start(); err != nil {
		return nil, err
	}
	defer watcher.Stop()

	timer := time.NewTimer(duration)
	defer timer.Stop()
	ticker := time.NewTicker(120 * time.Millisecond)
	defer ticker.Stop()
	for waiting := true; waiting; {
		select {
		case <-timer.C:
			waiting = false
		case <-ticker.C:
			status, err := watcher.GetStatus()
			if err != nil {
				return nil, err
			}
			if status == advertisement.BluetoothLEAdvertisementWatcherStatusAborted {
				return nil, errors.New("蓝牙扫描被 Windows 中止，请确认适配器可用且蓝牙已打开")
			}
		}
	}
	mu.Lock()
	defer mu.Unlock()
	list := make([]phone, 0, len(found))
	for _, item := range found {
		if item.Name == "" {
			item.Name = "翻页接收端"
		}
		list = append(list, item)
	}
	sort.Slice(list, func(i, j int) bool { return list[i].ID < list[j].ID })
	return list, nil
}

func advertisementAddressType(args *advertisement.BluetoothLEAdvertisementReceivedEventArgs) bluetooth.BluetoothAddressType {
	// The binding declares the v2 interface, but omits its public getter.
	guid := ole.NewGUID(advertisement.GUIDiBluetoothLEAdvertisementReceivedEventArgs2)
	var version *ole.IInspectable
	hr, _, _ := syscall.SyscallN(args.VTable().QueryInterface,
		uintptr(unsafe.Pointer(args)), uintptr(unsafe.Pointer(guid)), uintptr(unsafe.Pointer(&version)))
	if hr != 0 {
		return bluetooth.BluetoothAddressTypeUnspecified
	}
	defer version.Release()
	vtable := (*struct {
		ole.IInspectableVtbl
		getAddressType uintptr
	})(unsafe.Pointer(version.RawVTable))
	var kind bluetooth.BluetoothAddressType
	hr, _, _ = syscall.SyscallN(vtable.getAddressType, uintptr(unsafe.Pointer(version)), uintptr(unsafe.Pointer(&kind)))
	if hr != 0 {
		return bluetooth.BluetoothAddressTypeUnspecified
	}
	return kind
}

func advertisesService(adv *advertisement.BluetoothLEAdvertisement) bool {
	ids, err := adv.GetServiceUuids()
	if err != nil || ids == nil {
		return false
	}
	defer ids.Release()
	size, _ := ids.GetSize()
	wanted := *ole.NewGUID(serviceID)
	for i := uint32(0); i < size; i++ {
		// IVector<GUID>.GetAt writes a GUID, rather than an interface pointer.
		var id syscall.GUID
		result, _, _ := syscall.SyscallN(ids.VTable().GetAt,
			uintptr(unsafe.Pointer(ids)), uintptr(i), uintptr(unsafe.Pointer(&id)))
		if result == 0 && ole.GUID(id) == wanted {
			return true
		}
	}
	return false
}

func openBLE(target phone, secret [16]byte) (*bleLink, error) {
	device, err := asyncResult[bluetooth.BluetoothLEDevice](12 * time.Second)(
		bluetooth.BluetoothLEDeviceFromBluetoothAddressWithBluetoothAddressTypeAsync(target.address, target.kind))
	if err != nil {
		return nil, err
	}
	link := &bleLink{device: device, secret: secret}
	ready := false
	defer func() {
		if !ready {
			link.close()
		}
	}()
	if err := link.discover(); err != nil {
		return nil, err
	}
	if err := link.send(0); err != nil {
		return nil, err
	}
	ready = true
	return link, nil
}

func (l *bleLink) discover() error {
	servicesResult, err := asyncResult[gatt.GattDeviceServicesResult](15 * time.Second)(l.device.GetGattServicesWithCacheModeAsync(bluetooth.BluetoothCacheModeUncached))
	if err != nil {
		return err
	}
	defer servicesResult.Release()
	if status, err := servicesResult.GetStatus(); err != nil || status != gatt.GattCommunicationStatusSuccess {
		return gattStatus(status, err)
	}
	services, err := servicesResult.GetServices()
	if err != nil {
		return err
	}
	defer services.Release()
	count, err := services.GetSize()
	if err != nil {
		return err
	}
	wanted := syscall.GUID(*ole.NewGUID(serviceID))
	for i := uint32(0); i < count; i++ {
		ptr, err := services.GetAt(i)
		if err != nil {
			return err
		}
		item := (*gatt.GattDeviceService)(ptr)
		id, err := item.GetUuid()
		if err == nil && id == wanted {
			l.service = item
			break
		}
		item.Release()
	}
	if l.service == nil {
		return errors.New("手机上未找到翻页服务")
	}
	return l.discoverCharacteristics()
}

func (l *bleLink) discoverCharacteristics() error {
	result, err := asyncResult[gatt.GattCharacteristicsResult](12 * time.Second)(l.service.GetCharacteristicsWithCacheModeAsync(bluetooth.BluetoothCacheModeUncached))
	if err != nil {
		return err
	}
	defer result.Release()
	if status, err := result.GetStatus(); err != nil || status != gatt.GattCommunicationStatusSuccess {
		return gattStatus(status, err)
	}
	chars, err := result.GetCharacteristics()
	if err != nil {
		return err
	}
	defer chars.Release()
	count, err := chars.GetSize()
	if err != nil {
		return err
	}
	challenge := syscall.GUID(*ole.NewGUID(challengeID))
	command := syscall.GUID(*ole.NewGUID(commandID))
	for i := uint32(0); i < count; i++ {
		ptr, err := chars.GetAt(i)
		if err != nil {
			return err
		}
		item := (*gatt.GattCharacteristic)(ptr)
		id, err := item.GetUuid()
		switch {
		case err == nil && id == challenge && l.challenge == nil:
			l.challenge = item
		case err == nil && id == command && l.command == nil:
			l.command = item
		default:
			item.Release()
		}
	}
	if l.challenge == nil || l.command == nil {
		return errors.New("手机接收端版本不匹配")
	}
	return nil
}

func (l *bleLink) close() {
	if l.challenge != nil {
		l.challenge.Release()
	}
	if l.command != nil {
		l.command.Release()
	}
	if l.service != nil {
		_ = l.service.Close()
		l.service.Release()
	}
	_ = l.device.Close()
	l.device.Release()
	clear(l.secret[:])
}

func (l *bleLink) send(command byte) error {
	result, err := asyncResult[gatt.GattReadResult](5 * time.Second)(l.challenge.ReadValueWithCacheModeAsync(bluetooth.BluetoothCacheModeUncached))
	if err != nil {
		return err
	}
	defer result.Release()
	if status, err := result.GetStatus(); err != nil || status != gatt.GattCommunicationStatusSuccess {
		return gattStatus(status, err)
	}
	buffer, err := result.GetValue()
	if err != nil || buffer == nil {
		return errors.New("手机未返回验证数据")
	}
	defer buffer.Release()
	if size, err := buffer.GetLength(); err != nil || size != 16 {
		return errors.New("手机验证数据长度不正确")
	}
	reader, err := streams.DataReaderFromBuffer(buffer)
	if err != nil {
		return err
	}
	defer reader.Release()
	challenge, err := reader.ReadBytes(16)
	if err != nil {
		return err
	}
	packet := signedCommand(l.secret, [16]byte(challenge), command)
	writer, err := streams.NewDataWriter()
	if err != nil {
		return err
	}
	defer writer.Release()
	if err := writer.WriteBytes(uint32(len(packet)), packet[:]); err != nil {
		return err
	}
	value, err := writer.DetachBuffer()
	if err != nil {
		return err
	}
	defer value.Release()
	op, err := l.command.WriteValueWithOptionAsync(value, gatt.GattWriteOptionWriteWithResponse)
	if err != nil {
		return err
	}
	defer op.Release()
	if err := awaitWinRT(op, 5*time.Second); err != nil {
		return err
	}
	// The WinRT enum is written inline to the result slot, not dereferenced.
	status, err := op.GetResults()
	return gattStatus(gatt.GattCommunicationStatus(uintptr(status)), err)
}

func gattStatus(status gatt.GattCommunicationStatus, err error) error {
	if err != nil {
		return err
	}
	switch status {
	case gatt.GattCommunicationStatusSuccess:
		return nil
	case gatt.GattCommunicationStatusProtocolError, gatt.GattCommunicationStatusAccessDenied:
		return errPhoneRejected
	default:
		return fmt.Errorf("蓝牙服务不可达（%d）", status)
	}
}

func asyncResult[T any](timeout time.Duration) func(*foundation.IAsyncOperation, error) (*T, error) {
	return func(op *foundation.IAsyncOperation, err error) (*T, error) {
		if err != nil {
			return nil, err
		}
		defer op.Release()
		if err := awaitWinRT(op, timeout); err != nil {
			return nil, err
		}
		ptr, err := op.GetResults()
		if err == nil && ptr == nil {
			err = errors.New("Windows 未返回蓝牙设备或服务")
		}
		return (*T)(ptr), err
	}
}

func awaitWinRT(op *foundation.IAsyncOperation, timeout time.Duration) error {
	var info *foundation.IAsyncInfo
	guid := ole.NewGUID(foundation.GUIDIAsyncInfo)
	hresult, _, _ := syscall.SyscallN(op.VTable().QueryInterface,
		uintptr(unsafe.Pointer(op)), uintptr(unsafe.Pointer(guid)), uintptr(unsafe.Pointer(&info)))
	if hresult != 0 {
		return ole.NewError(hresult)
	}
	defer info.Release()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		status, err := info.GetStatus()
		if err != nil {
			return err
		}
		switch status {
		case foundation.AsyncStatusCompleted:
			return nil
		case foundation.AsyncStatusError, foundation.AsyncStatusCanceled:
			hresult, err := info.GetErrorCode()
			if err != nil {
				return err
			}
			return fmt.Errorf("Windows 蓝牙操作已结束（0x%08X）", uint32(hresult.Value))
		}
		time.Sleep(12 * time.Millisecond)
	}
	_ = info.Cancel()
	return errors.New("蓝牙响应超时")
}
