package main

import (
	"embed"
	"io/fs"
	"log"
	"os"
	"path/filepath"

	"github.com/egoist/mygo"
)

//go:embed ui/*
var uiFiles embed.FS

func main() {
	root, err := os.UserConfigDir()
	if err != nil {
		log.Fatal(err)
	}
	remote := newRemote(filepath.Join(root, "PhonePageRemote", "settings.json"))
	frontend, err := fs.Sub(uiFiles, "ui")
	if err != nil {
		log.Fatal(err)
	}
	mygo.App.SetName("PhonePageRemote")
	if os.Getenv("WEBVIEW2_USER_DATA_FOLDER") == "" {
		local := os.Getenv("LOCALAPPDATA")
		if local == "" {
			local = root
		}
		profile := filepath.Join(local, "PhonePageRemote", "Browser")
		if exe, err := os.Executable(); err == nil {
			portable := filepath.Join(filepath.Dir(exe), ".phonepage-browser")
			if err := os.MkdirAll(portable, 0700); err == nil {
				profile = portable
			}
		}
		_ = os.Setenv("WEBVIEW2_USER_DATA_FOLDER", profile)
	}
	mygo.SetFrontend(frontend)
	mygo.Bind(remote)
	mygo.App.WhenReady(func() {
		mygo.NewWindow(mygo.WindowOptions{
			Title: "手机翻页器", URL: "/", Width: 720, Height: 648,
			MinWidth: 510, MinHeight: 500, UseContentSize: true, BackgroundColor: "#FFFFFF",
			Page: mygo.PageOptions{DevTools: mygo.DevToolsDisabled},
		})
	})
	if err := mygo.App.Run(); err != nil {
		log.Fatal(err)
	}
}
