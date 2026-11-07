-- OBS Mobile Chat Relay Gateway & Tunnel
-- Allows mobile Android companion app to stream chat & alerts over 4G/5G mobile data
-- No router port forwarding or Tailscale app required.

obs = obslua

local tunnel_status = "Checking..."
local tunnel_host = "Loading..."
local tunnel_port = "Loading..."
local tunnel_ws_url = "Loading..."
local tunnel_mode = "Cloud (No Port Forwarding)"
local connected_device = "None"
local poll_timer_active = false

function script_description()
    return "<h3>📱 OBS Mobile Chat Relay Gateway</h3>" ..
           "<p>Generates an instant IP, Port, and Cloud Tunnel for the Android companion app.</p>" ..
           "<p>Connects over <b>4G/5G mobile data</b> with <b>zero router port forwarding</b> and <b>no Tailscale</b> required.</p>" ..
           "<p>Dock URL: <code>http://127.0.0.1:3000/tunnel-dock.html</code></p>"
end

function check_status()
    -- Query the local relay API using curl
    local handle = io.popen("curl.exe -s http://127.0.0.1:3000/api/tunnel-status 2>nul")
    if handle then
        local result = handle:read("*a")
        handle:close()
        if result and result:len() > 0 then
            -- Simple regex parse for key fields without external json library
            local status = result:match('"status"%s*:%s*"([^"]+)"')
            local host = result:match('"host"%s*:%s*"([^"]+)"')
            local port = result:match('"port"%s*:%s*([%d]+)')
            local wsUrl = result:match('"wsUrl"%s*:%s*"([^"]+)"')
            local mode = result:match('"mode"%s*:%s*"([^"]+)"')
            local device = result:match('"device"%s*:%s*"([^"]+)"')

            if status == "online" then
                tunnel_status = "🟢 ONLINE (Ready for Mobile)"
            elseif status == "starting" then
                tunnel_status = "🟡 STARTING TUNNEL..."
            else
                tunnel_status = "🔴 OFFLINE"
            end

            tunnel_host = host or "Not Assigned"
            tunnel_port = port or "443"
            tunnel_ws_url = wsUrl or "Unavailable"
            tunnel_mode = (mode == "local") and "Local Wi-Fi" or "Cloud Tunnel (4G/5G Cellular)"
            tunnel_device = device or "None"
            return
        end
    end
    tunnel_status = "🔴 Relay Server Not Running (Start Desktop App)"
end

function start_tunnel_clicked(props, p)
    os.execute('curl.exe -s -X POST -H "Content-Type: application/json" -d "{\\"mode\\":\\"cloud\\",\\"localPort\\":3000}" http://127.0.0.1:3000/api/tunnel-start >nul 2>&1')
    check_status()
    return true
end

function stop_tunnel_clicked(props, p)
    os.execute('curl.exe -s -X POST http://127.0.0.1:3000/api/tunnel-stop >nul 2>&1')
    check_status()
    return true
end

function open_dock_clicked(props, p)
    os.execute('start http://127.0.0.1:3000/tunnel-dock.html')
    return true
end

function copy_url_clicked(props, p)
    if tunnel_ws_url and tunnel_ws_url ~= "Unavailable" then
        os.execute('powershell -Command "Set-Clipboard -Value \'' .. tunnel_ws_url .. '\'"')
    end
    return true
end

function script_properties()
    local props = obs.obs_properties_create()

    obs.obs_properties_add_text(props, "status_label", "Gateway Status:", obs.OBS_TEXT_DEFAULT)
    obs.obs_properties_add_text(props, "mode_label", "Network Mode:", obs.OBS_TEXT_DEFAULT)
    obs.obs_properties_add_text(props, "host_label", "Mobile Host / IP:", obs.OBS_TEXT_DEFAULT)
    obs.obs_properties_add_text(props, "port_label", "Port:", obs.OBS_TEXT_DEFAULT)
    obs.obs_properties_add_text(props, "ws_url_label", "Mobile Connect URL:", obs.OBS_TEXT_DEFAULT)

    obs.obs_properties_add_button(props, "btn_copy", "📋 Copy Mobile URL to Clipboard", copy_url_clicked)
    obs.obs_properties_add_button(props, "btn_start", "🔄 Start / Reconnect Tunnel", start_tunnel_clicked)
    obs.obs_properties_add_button(props, "btn_stop", "⏹ Stop Tunnel", stop_tunnel_clicked)
    obs.obs_properties_add_button(props, "btn_open_dock", "📱 Open QR Code Dock in Browser", open_dock_clicked)

    return props
end

function script_update(settings)
    check_status()
    obs.obs_data_set_string(settings, "status_label", tunnel_status)
    obs.obs_data_set_string(settings, "mode_label", tunnel_mode)
    obs.obs_data_set_string(settings, "host_label", tunnel_host)
    obs.obs_data_set_string(settings, "port_label", tostring(tunnel_port))
    obs.obs_data_set_string(settings, "ws_url_label", tunnel_ws_url)
end

function script_load(settings)
    check_status()
    obs.timer_add(check_status, 4000)
end

function script_unload()
    obs.timer_remove(check_status)
end
