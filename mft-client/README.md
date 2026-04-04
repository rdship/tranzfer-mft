# TranzFer MFT Client

Cross-platform file transfer client for TranzFer MFT servers.

## How It Works

```
┌─────────────────────────────────────────────────────────────────────┐
│                        YOUR COMPUTER                                 │
│                                                                       │
│   ./outbox/          MFT Client           ./inbox/                   │
│   ┌──────────┐    ┌──────────────┐    ┌──────────┐                   │
│   │ Drop     │───>│ Auto-upload  │    │ Auto-    │                   │
│   │ files    │    │ to server    │    │ download │<── Server sends   │
│   │ here     │    │ /inbox       │    │ from     │    files here     │
│   └──────────┘    └──────┬───────┘    │ server   │                   │
│                          │            │ /outbox  │                   │
│   ./sent/                │            └──────────┘                   │
│   ┌──────────┐           │                                           │
│   │ Archive  │<──────────┘                                           │
│   │ of sent  │   (moved after                                        │
│   │ files    │    successful upload)                                  │
│   └──────────┘                                                       │
└──────────────────────────────────────────────────────────────────────┘
                           │
                    SFTP/FTP Connection
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    TranzFer MFT SERVER                                │
│                                                                       │
│   /inbox/     →   File Flow Pipeline   →   /outbox/ (or external)    │
│   (receive)       (decrypt, decompress,    (deliver to recipient)     │
│                    rename, compress,                                   │
│                    encrypt, route)                                     │
└──────────────────────────────────────────────────────────────────────┘
```

## Quick Start

### 1. Install

**Linux/macOS:**
```bash
./scripts/install.sh
cd ~/mft-client
```

**Windows:**
```cmd
scripts\install.bat
cd %USERPROFILE%\mft-client
```

**Or run directly:**
```bash
java -jar mft-client.jar --init     # Create config file
# Edit mft-client.yml
java -jar mft-client.jar             # Start syncing
```

### 2. Configure

Edit `mft-client.yml`:
```yaml
server:
  protocol: SFTP
  host: mft.yourcompany.com
  port: 2222
  username: your-account
  password: your-password

folders:
  outbox: ./outbox      # Drop files here → sent to server
  inbox: ./inbox        # Received files appear here
```

### 3. Transfer files

```bash
# Start the client
./mft-client

# In another terminal, copy a file to outbox
cp report.csv ~/mft-client/outbox/

# It uploads automatically. Check inbox for received files:
ls ~/mft-client/inbox/
```

## Client-to-Client Transfer

To transfer files between two remote clients through the server:

```
Client A (sender)              TranzFer Server              Client B (receiver)
─────────────────              ───────────────              ──────────────────
./outbox/report.csv  ──SFTP──>  /inbox/report.csv
                                    │
                               Flow Pipeline
                               (decrypt → compress)
                                    │
                                /outbox/report.csv.gz
                                    │
                     <──SFTP──  Client B polls /outbox
./inbox/report.csv.gz
```

**Setup:**
1. Admin creates accounts for Client A and Client B
2. Admin creates a folder mapping: Client A /inbox → Client B /outbox
3. Admin creates a flow: compress → route
4. Client A drops file in outbox → uploaded to server
5. Server processes and routes to Client B's outbox
6. Client B auto-downloads from server to local inbox

## Download Options

### Option A: Self-Contained Bundle (Recommended — NO Java required)

Pre-built packages with embedded lightweight JRE. Just extract and run:

| Platform | Download | Size |
|----------|----------|------|
| Linux x86_64 | `mft-client-linux-x64.tar.gz` | ~50MB |
| Linux ARM64 | `mft-client-linux-arm64.tar.gz` | ~50MB |
| macOS Intel | `mft-client-macos-x64.tar.gz` | ~50MB |
| macOS Apple Silicon | `mft-client-macos-arm64.tar.gz` | ~50MB |
| Windows x86_64 | `mft-client-windows-x64.zip` | ~50MB |

```bash
# Linux/macOS: extract and run
tar xzf mft-client-linux-x64.tar.gz
cd mft-client-linux-x64
# Edit mft-client.yml with your server details
./mft-client
```

```cmd
REM Windows: extract and run
REM Unzip mft-client-windows-x64.zip
REM Edit mft-client.yml with your server details
mft-client.bat
```

**Build bundles yourself** (for distribution to your partners):
```bash
./mft-client/scripts/bundle/build-bundles.sh
# Creates dist/mft-client-{platform}.tar.gz for all 5 platforms
```

### Option B: JAR only (requires Java 21+ installed)

| Platform | Status |
|----------|--------|
| Any OS with Java 21+ | `java -jar mft-client.jar` |

## Supported Platforms

| OS | Bundled JRE | Install Method |
|----|:-----------:|---------------|
| Linux x86_64 | ✅ | Extract `.tar.gz` and run `./mft-client` |
| Linux ARM64 (Raspberry Pi, Graviton) | ✅ | Extract `.tar.gz` and run `./mft-client` |
| macOS Intel | ✅ | Extract `.tar.gz` and run `./mft-client` |
| macOS Apple Silicon (M1/M2/M3/M4) | ✅ | Extract `.tar.gz` and run `./mft-client` |
| Windows 10/11 x86_64 | ✅ | Extract `.zip` and run `mft-client.bat` |
| Docker / Kubernetes | ✅ | `docker run` with Dockerfile |

> The bundled JRE is Eclipse Temurin 21 (lightweight ~45MB, headless).
> No Java installation required on the target machine.

---

## Firewall & Network Requirements

> **IMPORTANT**: Before running the client, ensure your network/firewall
> allows the required connections. The client only makes **outbound** connections
> — no inbound rules or port forwarding needed.

### Required Outbound Rules

| Direction | Protocol | Port | Destination | Purpose |
|-----------|----------|------|-------------|---------|
| **Outbound** | TCP | **2222** | MFT Server | SFTP data transfer (recommended) |
| Outbound | TCP | 21 | MFT Server | FTP command channel (alternative) |
| Outbound | TCP | 21000-21010 | MFT Server | FTP passive data ports |
| Outbound | TCP | 443 | MFT Server | HTTPS web portal (optional) |

> **Use SFTP (port 2222)** whenever possible — it only needs **one port**,
> works through NAT/proxies, and is fully encrypted.

### Testing Connectivity

Before starting the client, verify you can reach the server:

```bash
# Linux/macOS
nc -zv mft.yourcompany.com 2222     # Should say "Connection succeeded"

# Windows (PowerShell)
Test-NetConnection mft.yourcompany.com -Port 2222
```

If connection fails:
1. Ask your network admin to allow outbound TCP to the server on port 2222
2. Check if a VPN is required to reach the server
3. Verify the server hostname resolves: `nslookup mft.yourcompany.com`

### Corporate Proxy / NAT

| Protocol | Through Proxy? | Notes |
|----------|:--------------:|-------|
| SFTP | Usually OK | Uses single TCP connection, looks like SSH |
| FTP | Often blocked | Needs multiple ports; use SFTP instead |
| HTTPS | Yes | Works through all standard HTTP proxies |

---

## Run as Service

**Linux (systemd):**
```bash
sudo tee /etc/systemd/system/mft-client.service << UNIT
[Unit]
Description=TranzFer MFT Client
After=network.target
[Service]
User=mftuser
WorkingDirectory=/home/mftuser/mft-client
ExecStart=/usr/bin/java -jar /home/mftuser/mft-client/mft-client.jar
Restart=always
[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl enable --now mft-client
```

**Windows (NSSM):**
```cmd
nssm install MFT-Client java -jar C:\Users\you\mft-client\mft-client.jar
nssm start MFT-Client
```

**macOS (launchd):**
```bash
cat > ~/Library/LaunchAgents/com.tranzfer.mft-client.plist << PLIST
<?xml version="1.0"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.tranzfer.mft-client</string>
  <key>ProgramArguments</key><array>
    <string>java</string><string>-jar</string>
    <string>/Users/you/mft-client/mft-client.jar</string>
  </array>
  <key>WorkingDirectory</key><string>/Users/you/mft-client</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict></plist>
PLIST
launchctl load ~/Library/LaunchAgents/com.tranzfer.mft-client.plist
```
