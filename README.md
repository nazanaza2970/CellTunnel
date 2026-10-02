# cellularShare

Route your laptop's (Windows/Linux) network traffic through your phone's
cellular connection, over WiFi, using a virtual network interface — no root
required.

```
laptop                                   phone
TUN "celltun"                            TunnelService (foreground service)
   |                                       |
   v                                       |  listens on WiFi, port 12345
tun2socks v2 (SOCKS5 client)  ──WiFi──>  SOCKS5 server
                                          |
                                          v
                                   sockets bound to the
                                   CELLULAR network ──> internet
```

## Working principle

1. **Laptop** — `laptop/celltun` creates a TUN device (`celltun`) with
   tun2socks v2. Every packet the OS sends into the TUN device is converted
   into a SOCKS5 request (TCP via `CONNECT`, UDP/DNS via `UDP ASSOCIATE`) and
   sent over WiFi to the phone.
2. **Phone** — `TunnelService` accepts the SOCKS5 connections on port 12345.
   Each incoming connection (and each UDP relay datagram) is opened to the
   real target using a socket **bound to the cellular `Network`** via
   `ConnectivityManager.bindSocket(...)`, so the traffic leaves the phone
   through the mobile radio, not WiFi.
3. **Security** — the phone app can require a password (SOCKS5 username/
   password auth; username is fixed to `user`). Leave it empty for open
   access on the local WiFi.
4. **Visibility** — the phone UI lists every connected device (IP, active
   connections, bytes up/down, last seen, last target), refreshed every second.

Notes / limitations:

- TCP (including DNS over TCP) is guaranteed to exit on cellular. Raw UDP
  relay is best-effort: a `DatagramSocket` cannot be bound to a `Network` on
  Android without root, so it uses the phone's default network. The laptop is
  configured to use `1.1.1.1`, which works over plain TCP DNS (port 53), so
  name resolution is unaffected.
- The TUN device is a default route: while it runs, *all* laptop traffic goes
  through the phone. Stop `celltun` (Ctrl+C) and remove the default route to
  restore normal routing.
- Phone must stay on WiFi and have cellular data enabled. Android 8.0+
  (minSdk 26).

## Repository layout

```
laptop/   Go program (celltun) that creates the TUN device on Windows/Linux
phone/    Android app (Kotlin) that runs the SOCKS5-to-cellular tunnel
```

## Laptop: build and install

Requirements: Go 1.21+ (module uses `go 1.22`+ features via dependencies).

```sh
cd laptop
go build -o celltun .        # Linux
# Windows:
go build -o celltun.exe .
```

The binary has two modes: **GUI** (no arguments) and **CLI** (any flag).

### GUI (Wails)

Running the binary with no arguments opens a small desktop app: input fields
for every CLI flag, Start/Stop buttons, live status + process log, and a
collapsible step-by-step connection guide. **Apply network setup / Undo**
buttons run the TUN address/route/DNS commands for you (Linux needs the app
launched with `sudo`; Windows needs admin).

Build the GUI binary:

- Linux: system packages `libgtk-3-dev libwebkit2gtk-4.1-dev libnotify-dev
  libxss-dev`, then:

  ```sh
  cd laptop
  go run github.com/wailsapp/wails/v2/cmd/wails@v2.16.0 build -tags webkit2_41
  # output: build/bin/celltun
  ```

- Windows: same `wails build` command (no system packages needed).

Run it: `./build/bin/celltun` (Linux, `sudo` for TUN creation) or
`celltun.exe` in an elevated terminal (Windows).

### CLI mode

```sh
# Linux (TUN creation needs root):
sudo ./celltun -proxy <phone-wifi-ip>:12345 -pass <password>

# Windows (run in an elevated terminal):
celltun.exe -proxy <phone-wifi-ip>:12345 -pass <password>
```

- `-proxy` — the phone's IP on your WiFi network, default `192.168.4.2:12345`.
- `-pass` — the password set in the phone app (omit if left empty).
- `-device` — TUN device name (default `celltun`).
- `-mtu` — default `1400`.

### Configure the TUN interface

tun2socks v2 only creates the TUN device; you assign the address and route
yourself. The program prints these commands on start.

Linux (as root):

```sh
sudo ip addr add 198.18.0.1/32 dev celltun
sudo ip route add default dev celltun
sudo sh -c 'echo nameserver 1.1.1.1 > /etc/resolv.conf'
```

Windows (elevated):

```bat
netsh interface ipv4 set address "celltun" static 198.18.0.1 255.255.255.255
netsh interface ipv4 add route 0.0.0.0 0.0.0.0 celltun
netsh interface ipv4 set dns celltun static 1.1.1.1
```

To undo later: `sudo ip addr del 198.18.0.1/32 dev celltun && sudo ip route del default dev celltun`
(Windows: `netsh interface ipv4 delete route 0.0.0.0 0.0.0.0`).

Verify: `curl ifconfig.me` should return your phone's cellular IP.

## Android: build

Requirements: Android Studio (Hedgehog or newer) with the Android SDK
platform 34, JDK 17. AGP 8.2.2 / Kotlin 1.9.22 are declared in the project.

1. Open the `phone/` folder in Android Studio (**File > Open**).
2. Let Gradle sync (downloads AGP, Kotlin, SDK 34).
3. Build an APK: **Build > Build Bundle(s)/APK(s) > Build APK(s)**, or from
   a terminal with Gradle 8.2+ installed:

   ```sh
   cd phone
   gradle assembleDebug
   ```

4. APK location: `phone/app/build/outputs/apk/debug/app-debug.apk`.
   Copy it to the phone and install (enable "install unknown apps" if
   prompted), or install straight from Android Studio with a phone plugged in.

There are no external dependencies beyond the Android platform itself.

## Usage

1. Phone: open **CellTunnel**, optionally type a password and **Save
   password**, then **Start tunnel**. A notification stays visible while it
   runs.
2. Laptop: launch the GUI (no args) and press **Start tunnel** and
   **Apply network setup**, or run the CLI with the phone's WiFi IP
   (and password) and apply the TUN/route/DNS commands above.
3. The phone UI now shows your laptop under **Connected devices** with live
   counters.
4. To stop: **Stop tunnel** on the phone and/or Ctrl+C `celltun` on the
   laptop, then remove the default route.
