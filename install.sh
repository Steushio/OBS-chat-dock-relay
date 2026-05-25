#!/bin/bash
# install.sh - OBS Chat Relay Installer for Linux & macOS
set -e

echo -e "\033[0;36m======================================\033[0m"
echo -e "\033[0;36m   OBS Chat Relay Installer for Unix  \033[0m"
echo -e "\033[0;36m======================================\033[0m"

# 1. Check Node.js & Auto-Install if missing
if command -v node >/dev/null 2>&1; then
    echo -e "\033[0;32m[√] Node.js is already installed.\033[0m"
else
    echo -e "\033[0;33m[!] Node.js not found. Attempting automatic installation...\033[0m"
    
    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS Detection
        if command -v brew >/dev/null 2>&1; then
            echo -e "\033[0;36m[*] Installing Node.js via Homebrew...\033[0m"
            brew install node
        else
            echo -e "\033[0;33m[!] Homebrew not found. Downloading official Node.js installer for macOS...\033[0m"
            curl -L "https://nodejs.org/dist/v20.11.0/node-v20.11.0.pkg" -o "/tmp/node.pkg"
            echo -e "\033[0;36m[*] Running installer (may request administrator password)...\033[0m"
            sudo installer -pkg "/tmp/node.pkg" -target /
            rm "/tmp/node.pkg"
        fi
    elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
        # Linux Detection
        if command -v apt-get >/dev/null 2>&1; then
            echo -e "\033[0;36m[*] Installing Node.js via APT...\033[0m"
            sudo apt-get update && sudo apt-get install -y nodejs npm
        elif command -v dnf >/dev/null 2>&1; then
            echo -e "\033[0;36m[*] Installing Node.js via DNF...\033[0m"
            sudo dnf install -y nodejs
        else
            echo -e "\033[0;31m[x] Package manager not recognized. Please install Node.js manually.\033[0m"
            exit 1
        fi
    else
        echo -e "\033[0;31m[x] Unsupported platform. Please install Node.js manually.\033[0m"
        exit 1
    fi
fi

# 2. Download source zip
INSTALL_DIR="$HOME/OBS_CHAT"
if [ -d "$INSTALL_DIR" ]; then
    echo -e "\033[0;33m[!] Target directory $INSTALL_DIR already exists. Updating...\033[0m"
else
    mkdir -p "$INSTALL_DIR"
fi

echo -e "\033[0;36m[*] Downloading OBS Chat Relay from GitHub...\033[0m"

# Download zip directly from GitHub
REPO="https://github.com/Steushio/OBS-chat-dock-relay"
RELEASE_URL=$(curl -sL $REPO/releases/latest | grep -oE '/Steushio/OBS-chat-dock-relay/releases/download/[^\"]+')
if [ -z "$RELEASE_URL" ]; then
  ZIP_URL="$REPO/archive/refs/heads/main.zip"
else
  ZIP_URL="$REPO$RELEASE_URL"
fi
curl -L "$ZIP_URL" -o "/tmp/obs-chat-main.zip"

echo -e "\033[0;36m[*] Extracting application files...\033[0m"
unzip -qo "/tmp/obs-chat-main.zip" -d "/tmp/obs-chat-extracted"

# Move files to target directory
# Zip from GitHub branch archive is nested inside OBS-chat-dock-relay-main/
if [ -d "/tmp/obs-chat-extracted/OBS-chat-dock-relay-main" ]; then
  cp -r /tmp/obs-chat-extracted/OBS-chat-dock-relay-main/* "$INSTALL_DIR"
else
  # Fallback in case of a tagged release zip structure
  cp -r /tmp/obs-chat-extracted/*/* "$INSTALL_DIR" 2>/dev/null || cp -r /tmp/obs-chat-extracted/* "$INSTALL_DIR"
fi

# Clean up
rm "/tmp/obs-chat-main.zip"
rm -rf "/tmp/obs-chat-extracted"

cd "$INSTALL_DIR"

# 3. Install packages
echo -e "\033[0;36m[*] Installing dependencies...\033[0m"
npm install --no-audit --no-fund

# 4. Create App Menu Entry
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    # ── Linux: .desktop file ──
    DESKTOP_DIR="$HOME/.local/share/applications"
    mkdir -p "$DESKTOP_DIR"
    
    ICON_PATH="$INSTALL_DIR/src/assets/icon.png"
    DESKTOP_FILE="$DESKTOP_DIR/obs-chat-relay.desktop"

    cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Name=OBS Chat Relay
Comment=YouTube Live Chat & Alert Capture for OBS
Exec=bash -c "cd $INSTALL_DIR && npm start"
Icon=$ICON_PATH
Terminal=false
Type=Application
Categories=Utility;AudioVideo;
StartupWMClass=obs-chat-relay
EOF

    chmod +x "$DESKTOP_FILE"
    
    # Update desktop database if available
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
    fi
    
    echo -e "\033[0;32m[√] Linux app menu entry created! Search 'OBS Chat Relay' in your app launcher.\033[0m"

elif [[ "$OSTYPE" == "darwin"* ]]; then
    # ── macOS: .app bundle in /Applications ──
    APP_DIR="/Applications/OBS Chat Relay.app"
    MACOS_DIR="$APP_DIR/Contents/MacOS"
    RESOURCES_DIR="$APP_DIR/Contents/Resources"
    
    mkdir -p "$MACOS_DIR"
    mkdir -p "$RESOURCES_DIR"
    
    # Copy icon
    if [ -f "$INSTALL_DIR/src/assets/icon.png" ]; then
        cp "$INSTALL_DIR/src/assets/icon.png" "$RESOURCES_DIR/icon.png"
    fi
    
    # Create launcher script
    cat > "$MACOS_DIR/obs-chat-relay" <<EOF
#!/bin/bash
cd "$INSTALL_DIR"
npm start
EOF
    chmod +x "$MACOS_DIR/obs-chat-relay"
    
    # Create Info.plist
    cat > "$APP_DIR/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>obs-chat-relay</string>
    <key>CFBundleName</key>
    <string>OBS Chat Relay</string>
    <key>CFBundleIdentifier</key>
    <string>com.obs.chat.relay</string>
    <key>CFBundleVersion</key>
    <string>1.0.0</string>
    <key>CFBundleIconFile</key>
    <string>icon.png</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
</dict>
</plist>
EOF
    
    echo -e "\033[0;32m[√] macOS app created! Find 'OBS Chat Relay' in your Applications folder and Launchpad.\033[0m"
fi

# 5. Launch
echo -e "\033[0;32m[√] Installation complete! Starting OBS Chat Relay...\033[0m"
npm start
