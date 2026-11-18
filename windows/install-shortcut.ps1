param(
    [string] $ExePath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist\PhonePageRemote.exe'),
    # Optional intermediary that starts the app on its behalf. Needed when this checkout
    # lives under a folder carrying a Low mandatory-integrity label: Explorer refuses to
    # shell-launch an unsigned low-integrity image (ShellExecuteEx returns 1223 /
    # SE_ERR_ACCESSDENIED) while CreateProcess still works, so double-clicking a shortcut
    # that points straight at the exe does nothing. A launcher outside the labelled folder
    # starts the exe through CreateProcess and double-click works again.
    [string] $LauncherPath = ''
)

$ErrorActionPreference = 'Stop'
$exe = Get-Item -LiteralPath $ExePath
if ($exe.PSIsContainer -or $exe.Extension -ine '.exe') { throw 'Choose the PhonePageRemote.exe file.' }
$desktop = [Environment]::GetFolderPath('DesktopDirectory')
if (-not $desktop -or -not (Test-Path -LiteralPath $desktop -PathType Container)) {
    throw 'The current Windows desktop folder is unavailable.'
}

# Construct the filename from code points for Windows PowerShell's ANSI script reader.
$title = -join [char[]] @(0x624B, 0x673A, 0x7FFB, 0x9875, 0x5668)
$linkPath = Join-Path $desktop "$title.lnk"
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($linkPath)

$target = $exe.FullName
if ($LauncherPath -ne '') {
    $launcher = Get-Item -LiteralPath $LauncherPath
    if ($launcher.PSIsContainer -or $launcher.Extension -ine '.exe') { throw 'Choose a .exe for -LauncherPath.' }
    $target = $launcher.FullName
}

if ((Test-Path -LiteralPath $linkPath) -and $link.TargetPath -ne '') {
    $existing = $link.TargetPath
    $known = ($existing -ieq $exe.FullName) -or ($existing -ieq $target) -or
             ((Split-Path -Leaf $existing) -ieq 'ppr-launch.exe')
    if (-not $known) {
        throw 'A desktop shortcut already uses this name and points to a different program.'
    }
}

$link.TargetPath = $target
$link.WorkingDirectory = (Split-Path -Parent $target)
$link.IconLocation = "$($exe.FullName),0"
$link.Description = $title
$link.Save()
Get-Item -LiteralPath $linkPath
