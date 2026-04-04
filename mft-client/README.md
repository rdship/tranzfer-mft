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

## Supported Platforms

| OS | Status | Install Method |
|----|--------|---------------|
| Linux (x86_64, arm64) | ✅ | `install.sh` or `java -jar` |
| macOS (Intel, Apple Silicon) | ✅ | `install.sh` or `java -jar` |
| Windows 10/11 | ✅ | `install.bat` or `java -jar` |
| Docker | ✅ | `docker run` |

Requires: **Java 21+** (any JVM — Temurin, Corretto, Zulu, etc.)

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
