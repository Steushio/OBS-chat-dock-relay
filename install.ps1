# install.ps1 - OBS Chat Relay Installer for Windows
$ErrorActionPreference = "Stop"

Write-Host "======================================" -ForegroundColor Cyan
Write-Host "   OBS Chat Relay Installer for Windows" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan

# 1. Check/Install Node.js
if (Get-Command node -ErrorAction SilentlyContinue) {
    Write-Host "[√] Node.js is already installed." -ForegroundColor Green
} else {
    Write-Host "[!] Node.js not found. Installing Node.js via winget..." -ForegroundColor Yellow
    
    # Check if winget is available
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Write-Host "[x] winget package manager was not found. Please install Node.js manually from https://nodejs.org/" -ForegroundColor Red
        Exit
    }

    winget install OpenJS.NodeJS --silent --accept-source-agreements --accept-package-agreements
    
    # Update environment variables for the current session to detect node
    $env:Path += ";C:\Program Files\nodejs"
    Write-Host "[√] Node.js successfully installed!" -ForegroundColor Green
}

# 2. Download source directly (no Git required)
$installDir = "$HOME\OBS_CHAT"
if (Test-Path $installDir) {
    Write-Host "[!] Target directory $installDir already exists. Updating..." -ForegroundColor Yellow
} else {
    New-Item -ItemType Directory -Path $installDir | Out-Null
}

Write-Host "[*] Downloading OBS Chat Relay from GitHub..." -ForegroundColor Cyan

# Download zip directly from GitHub
$zipUrl = "https://github.com/Steushio/OBS-chat-dock-relay/archive/refs/heads/main.zip"
$zipPath = "$env:TEMP\obs-chat-main.zip"

Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath

Write-Host "[*] Extracting application files..." -ForegroundColor Cyan
Expand-Archive -Path $zipPath -DestinationPath "$env:TEMP\obs-chat-extracted" -Force

# Move files to target directory
if (Test-Path "$env:TEMP\obs-chat-extracted\OBS-chat-dock-relay-main") {
    Copy-Item -Path "$env:TEMP\obs-chat-extracted\OBS-chat-dock-relay-main\*" -Destination $installDir -Recurse -Force
} else {
    Copy-Item -Path "$env:TEMP\obs-chat-extracted\*\*" -Destination $installDir -Recurse -Force
}

# Clean up temp files
Remove-Item -Path $zipPath -Force
Remove-Item -Path "$env:TEMP\obs-chat-extracted" -Recurse -Force

Set-Location -Path $installDir

# 3. Install NPM packages
Write-Host "[*] Installing application dependencies (NPM)..." -ForegroundColor Cyan
npm install --no-audit --no-fund

# 4. Create Windows Start Menu Shortcut
Write-Host "[*] Creating Start Menu shortcut..." -ForegroundColor Cyan

$startMenuDir = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs"
$shortcutPath = "$startMenuDir\OBS Chat Relay.lnk"

# Find npm and node paths
$npmPath = (Get-Command npm -ErrorAction SilentlyContinue).Source
$nodePath = (Get-Command node -ErrorAction SilentlyContinue).Source

# Use WScript.Shell COM object to create a proper .lnk shortcut
$WshShell = New-Object -ComObject WScript.Shell
$shortcut = $WshShell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $nodePath
$shortcut.Arguments = "`"$npmPath`" start"
$shortcut.WorkingDirectory = $installDir
$shortcut.Description = "YouTube Live Chat & Alert Capture for OBS"
$shortcut.WindowStyle = 7  # Minimized (hides the terminal window)

# Set custom icon if .ico exists
$icoPath = "$installDir\src\assets\icon.ico"
if (Test-Path $icoPath) {
    $shortcut.IconLocation = "$icoPath, 0"
}

$shortcut.Save()

Write-Host "[√] Start Menu shortcut created! Search 'OBS Chat Relay' in the Start Menu." -ForegroundColor Green

# Also create a Desktop shortcut
$desktopShortcutPath = "$HOME\Desktop\OBS Chat Relay.lnk"
$desktopShortcut = $WshShell.CreateShortcut($desktopShortcutPath)
$desktopShortcut.TargetPath = $nodePath
$desktopShortcut.Arguments = "`"$npmPath`" start"
$desktopShortcut.WorkingDirectory = $installDir
$desktopShortcut.Description = "YouTube Live Chat & Alert Capture for OBS"
$desktopShortcut.WindowStyle = 7

if (Test-Path $icoPath) {
    $desktopShortcut.IconLocation = "$icoPath, 0"
}

$desktopShortcut.Save()

Write-Host "[√] Desktop shortcut created!" -ForegroundColor Green

# 5. Launch Application
Write-Host "[√] Installation complete! Starting OBS Chat Relay..." -ForegroundColor Green
npm start
