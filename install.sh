#!/bin/bash
# install.sh - OBS Chat Relay Installer for Linux & macOS
set -e

echo -e "\033[0;36m======================================\033[0m"
echo -e "\033[0;36m   OBS Chat Relay Installer for Unix  \033[0m"
echo -e "\033[0;36m======================================\033[0m"

# 1. Check Node.js, NPM, and Git & Auto-Install if missing
check_and_install_dependencies() {
    if ! command -v git >/dev/null 2>&1 || ! command -v node >/dev/null 2>&1; then
        echo -e "\033[0;33m[!] Missing required dependencies. Attempting automatic installation...\033[0m"
        
        if [[ "$OSTYPE" == "darwin"* ]]; then
            # macOS
            if command -v brew >/dev/null 2>&1; then
                echo -e "\033[0;36m[*] Installing Node.js & Git via Homebrew...\033[0m"
                brew install node git
            else
                echo -e "\033[0;31m[x] Homebrew not found. Please install Node.js and Git manually.\033[0m"
                exit 1
            fi
        elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
            # Linux
            if command -v apt-get >/dev/null 2>&1; then
                echo -e "\033[0;36m[*] Installing Node.js, NPM, & Git via APT...\033[0m"
                sudo apt-get update && sudo apt-get install -y nodejs npm git
            elif command -v dnf >/dev/null 2>&1; then
                echo -e "\033[0;36m[*] Installing Node.js & Git via DNF...\033[0m"
                sudo dnf install -y nodejs git
            else
                echo -e "\033[0;31m[x] Package manager not recognized. Please install Node.js and Git manually.\033[0m"
                exit 1
            fi
        else
            echo -e "\033[0;31m[x] Unsupported platform. Please install Node.js and Git manually.\033[0m"
            exit 1
        fi
    else
        echo -e "\033[0;32m[√] All core dependencies (Node.js, Git) are already installed.\033[0m"
    fi
}

check_and_install_dependencies

# 2. Clone/Update Source Repository
INSTALL_DIR="$HOME/OBS_CHAT"
REPO_URL="https://github.com/Steushio/OBS-chat-dock-relay.git"

if [ -d "$INSTALL_DIR" ]; then
    if [ -d "$INSTALL_DIR/.git" ]; then
        echo -e "\033[0;32m[√] Repository already exists. Pulling latest updates...\033[0m"
        cd "$INSTALL_DIR"
        git pull origin main
    else
        echo -e "\033[0;33m[!] Target directory $INSTALL_DIR exists but is not a Git repository. Re-cloning...\033[0m"
        rm -rf "$INSTALL_DIR"
        git clone "$REPO_URL" "$INSTALL_DIR"
    fi
else
    echo -e "\033[0;36m[*] Cloning OBS Chat Relay from GitHub...\033[0m"
    git clone "$REPO_URL" "$INSTALL_DIR"
fi
cd "$INSTALL_DIR"

# 3. Install packages
echo -e "\033[0;36m[*] Installing dependencies...\033[0m"
npm install --no-audit --no-fund --unsafe-perm=true

# Force Electron to install its binary in case postinstall skipped
if [ -f "node_modules/electron/install.js" ]; then
    echo -e "\033[0;36m[*] Verifying and installing Electron binary...\033[0m"
    node node_modules/electron/install.js
fi


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
