import obspython as obs
import urllib.request
import json
import subprocess

tunnel_status = "Checking..."
tunnel_host = "Loading..."
tunnel_port = "Loading..."
tunnel_ws_url = "Loading..."
tunnel_mode = "Cloud (No Port Forwarding)"

def script_description():
    return """<h3>📱 OBS Mobile Chat Relay Gateway (Python)</h3>
<p>Generates an instant IP, Port, and Cloud Tunnel for the Android companion app.</p>
<p>Connects over <b>4G/5G mobile data</b> with <b>zero router port forwarding</b> and <b>no Tailscale</b> required.</p>
<p>Dock URL: <code>http://127.0.0.1:3000/tunnel-dock.html</code></p>"""

def check_status():
    global tunnel_status, tunnel_host, tunnel_port, tunnel_ws_url, tunnel_mode
    try:
        req = urllib.request.Request("http://127.0.0.1:3000/api/tunnel-status", headers={'User-Agent': 'OBS-Plugin'})
        with urllib.request.urlopen(req, timeout=2) as response:
            if response.status == 200:
                data = json.loads(response.read().decode('utf-8'))
                st = data.get('status', 'offline')
                if st == 'online':
                    tunnel_status = "🟢 ONLINE (Ready for Mobile)"
                elif st == 'starting':
                    tunnel_status = "🟡 STARTING TUNNEL..."
                else:
                    tunnel_status = f"🔴 {data.get('errorMessage', 'Offline')}"

                tunnel_host = data.get('host') or "Not Assigned"
                tunnel_port = str(data.get('port') or "443")
                tunnel_ws_url = data.get('wsUrl') or "Unavailable"
                tunnel_mode = "Local Wi-Fi" if data.get('mode') == 'local' else "Cloud Tunnel (4G/5G Cellular)"
                return
    except Exception:
        pass
    tunnel_status = "🔴 Relay Server Offline (Start Desktop App)"

def start_tunnel_clicked(props, p):
    try:
        req = urllib.request.Request(
            "http://127.0.0.1:3000/api/tunnel-start",
            data=json.dumps({"mode": "cloud", "localPort": 3000}).encode('utf-8'),
            headers={'Content-Type': 'application/json', 'User-Agent': 'OBS-Plugin'}
        )
        urllib.request.urlopen(req, timeout=3)
    except Exception:
        pass
    check_status()
    return True

def stop_tunnel_clicked(props, p):
    try:
        req = urllib.request.Request(
            "http://127.0.0.1:3000/api/tunnel-stop",
            data=b"{}",
            headers={'Content-Type': 'application/json', 'User-Agent': 'OBS-Plugin'}
        )
        urllib.request.urlopen(req, timeout=3)
    except Exception:
        pass
    check_status()
    return True

def copy_url_clicked(props, p):
    if tunnel_ws_url and tunnel_ws_url != "Unavailable":
        subprocess.run(["powershell", "-Command", f"Set-Clipboard -Value '{tunnel_ws_url}'"], shell=True)
    return True

def open_dock_clicked(props, p):
    subprocess.run(["cmd", "/c", "start", "http://127.0.0.1:3000/tunnel-dock.html"], shell=True)
    return True

def script_properties():
    props = obs.obs_properties_create()
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

def script_load(settings):
    check_status()
    obs.timer_add(check_status, 4000)

def script_unload():
    obs.timer_remove(check_status)
