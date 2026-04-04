#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT Client — Installer (Linux/macOS)
# =============================================================================
set -e

VERSION="2.1.0"
INSTALL_DIR="${MFT_INSTALL_DIR:-$HOME/mft-client}"
JAR_NAME="mft-client-1.0.0-SNAPSHOT.jar"

echo ""
echo "  ╔══════════════════════════════════════╗"
echo "  ║  TranzFer MFT Client Installer      ║"
echo "  ╚══════════════════════════════════════╝"
echo ""

# Check Java
if ! command -v java &>/dev/null; then
    echo "  ✗ Java not found. Install Java 21+:"
    echo "    macOS:  brew install openjdk@21"
    echo "    Ubuntu: sudo apt install openjdk-21-jre"
    echo "    RHEL:   sudo dnf install java-21-openjdk"
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
echo "  ✓ Java $JAVA_VER found"

# Create install directory
echo "  Installing to: $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"

# Copy JAR (if running from build directory)
if [ -f "target/$JAR_NAME" ]; then
    cp "target/$JAR_NAME" "$INSTALL_DIR/mft-client.jar"
elif [ -f "mft-client.jar" ]; then
    cp "mft-client.jar" "$INSTALL_DIR/mft-client.jar"
else
    echo "  ✗ Cannot find mft-client JAR. Build first:"
    echo "    mvn clean package -DskipTests -pl mft-client"
    exit 1
fi

# Create default config
if [ ! -f "$INSTALL_DIR/mft-client.yml" ]; then
    cat > "$INSTALL_DIR/mft-client.yml" << 'YML'
server:
  protocol: SFTP
  host: YOUR_SERVER_HOST
  port: 2222
  username: YOUR_USERNAME
  password: YOUR_PASSWORD
  timeoutSeconds: 30
  autoRetry: true
  maxRetries: 3

folders:
  outbox: ./outbox
  inbox: ./inbox
  sent: ./sent
  failed: ./failed
  remoteInbox: /inbox
  remoteOutbox: /outbox

sync:
  watchOutbox: true
  pollInbox: true
  pollIntervalSeconds: 30
  deleteAfterDownload: true

clientName: mft-client
logLevel: INFO
YML
fi

# Create launcher script
cat > "$INSTALL_DIR/mft-client" << 'LAUNCHER'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
exec java -jar "$DIR/mft-client.jar" "$@"
LAUNCHER
chmod +x "$INSTALL_DIR/mft-client"

# Create working directories
mkdir -p "$INSTALL_DIR/outbox" "$INSTALL_DIR/inbox" "$INSTALL_DIR/sent" "$INSTALL_DIR/failed"

# Optional: create systemd service (Linux)
if [ "$(uname)" = "Linux" ] && command -v systemctl &>/dev/null; then
    echo ""
    echo "  To run as a background service:"
    echo "    sudo tee /etc/systemd/system/mft-client.service << EOF"
    echo "    [Unit]"
    echo "    Description=TranzFer MFT Client"
    echo "    After=network.target"
    echo "    [Service]"
    echo "    Type=simple"
    echo "    User=$(whoami)"
    echo "    WorkingDirectory=$INSTALL_DIR"
    echo "    ExecStart=$INSTALL_DIR/mft-client"
    echo "    Restart=always"
    echo "    [Install]"
    echo "    WantedBy=multi-user.target"
    echo "    EOF"
    echo "    sudo systemctl daemon-reload"
    echo "    sudo systemctl enable --now mft-client"
fi

# Optional: create macOS launchd plist
if [ "$(uname)" = "Darwin" ]; then
    echo ""
    echo "  To run at login (macOS):"
    echo "    Create ~/Library/LaunchAgents/com.tranzfer.mft-client.plist"
fi

echo ""
echo "  ✓ Installation complete!"
echo ""
echo "  Next steps:"
echo "    1. cd $INSTALL_DIR"
echo "    2. Edit mft-client.yml with your server details"
echo "    3. Run: ./mft-client"
echo "    4. Drop files into ./outbox to transfer"
echo ""
