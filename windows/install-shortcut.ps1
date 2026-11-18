param([string] $ExePath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist\PhonePageRemote.exe'))

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
if ((Test-Path -LiteralPath $linkPath) -and $link.TargetPath -ine $exe.FullName) {
    throw 'A desktop shortcut already uses this name and points to a different program.'
}
$link.TargetPath = $exe.FullName
$link.WorkingDirectory = $exe.DirectoryName
$link.IconLocation = "$($exe.FullName),0"
$link.Description = $title
$link.Save()
Get-Item -LiteralPath $linkPath
