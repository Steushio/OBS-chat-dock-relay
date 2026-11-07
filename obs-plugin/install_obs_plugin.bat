@echo off
echo ========================================================
echo Installing OBS Mobile Relay Plugin for OBS Studio
echo ========================================================

set "TARGET_DIR=%APPDATA%\obs-studio\plugin_config\scripts"
if not exist "%TARGET_DIR%" mkdir "%TARGET_DIR%"

copy /Y "%~dp0obs_mobile_relay.lua" "%TARGET_DIR%\obs_mobile_relay.lua"
copy /Y "%~dp0obs_mobile_relay.py" "%TARGET_DIR%\obs_mobile_relay.py"

echo.
echo [SUCCESS] OBS Plugin installed to:
echo %TARGET_DIR%
echo.
echo Next Steps in OBS Studio:
echo 1. Open OBS Studio
echo 2. Click 'Tools' -> 'Scripts'
echo 3. Click '+' and select: %TARGET_DIR%\obs_mobile_relay.lua
echo 4. Go to 'Docks' -> 'Custom Browser Docks...'
echo    Name: Mobile Connect Dock
echo    URL:  http://127.0.0.1:3000/tunnel-dock.html
echo ========================================================
pause
