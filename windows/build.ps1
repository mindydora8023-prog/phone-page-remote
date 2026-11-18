param([string] $OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist'))

$ErrorActionPreference = 'Stop'
$toolDir = Join-Path (Split-Path -Parent $PSScriptRoot) '.toolchain'
$bundledGo = Join-Path $toolDir 'go\go\bin\go.exe'
$go = if (Test-Path -LiteralPath $bundledGo) { $bundledGo } else { (Get-Command go.exe).Source }
$env:Path = (Split-Path -Parent $go) + ';' + $env:Path
$env:GOPATH = Join-Path $toolDir 'gopath'
$env:GOBIN = Join-Path $toolDir 'bin'
$env:GOMODCACHE = Join-Path $toolDir 'modules'
$env:GOCACHE = Join-Path $toolDir 'cache'
$env:GOTMPDIR = Join-Path $toolDir 'temp'
$env:CGO_ENABLED = '0'
$env:GOOS = 'windows'
$env:GOARCH = 'amd64'
$targetDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $env:GOTMPDIR, $env:GOBIN, $targetDir | Out-Null

Push-Location $PSScriptRoot
try {
    & $go install github.com/mygo-lang/mygo/cmd/mygo@v0.1.3
    if ($LASTEXITCODE) { throw 'MyGO compiler build failed.' }
    New-Item -ItemType Directory -Force -Path '.\gesture\zz_gesture' | Out-Null
    & (Join-Path $env:GOBIN 'mygo.exe') --no-prelude sync gesture
    if ($LASTEXITCODE) { throw 'MyGO gesture generation failed.' }
    & $go test -timeout 30s ./...
    if ($LASTEXITCODE) { throw 'Windows tests failed.' }
    & $go vet ./...
    if ($LASTEXITCODE) { throw 'Go vet failed.' }
    $icon = Join-Path $env:GOTMPDIR 'PhonePageRemote.ico'
    & .\assets\make-icon.ps1 -OutputPath $icon
    & $go run github.com/akavel/rsrc@v0.10.2 -ico $icon -arch amd64 -o 'rsrc_windows_amd64.syso'
    if ($LASTEXITCODE) { throw 'Windows icon embedding failed.' }
    $output = Join-Path $targetDir 'PhonePageRemote.exe'
    & $go build -trimpath -ldflags '-s -w -H=windowsgui' -o $output .
    if ($LASTEXITCODE) { throw 'Windows build failed.' }
    Get-Item -LiteralPath $output
} finally {
    Pop-Location
}
