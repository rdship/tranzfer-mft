#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT Client — Build Self-Contained Bundles
# =============================================================================
# Creates ready-to-run packages with embedded JRE for each platform.
# No Java installation required on the target machine.
#
# Output:
#   dist/mft-client-linux-x64.tar.gz       (Linux x86_64)
#   dist/mft-client-linux-arm64.tar.gz      (Linux ARM64)
#   dist/mft-client-macos-x64.tar.gz        (macOS Intel)
#   dist/mft-client-macos-arm64.tar.gz       (macOS Apple Silicon)
#   dist/mft-client-windows-x64.zip          (Windows x86_64)
#
# Prerequisites: curl, tar, zip (all pre-installed on macOS/Linux)
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLIENT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_DIR="$CLIENT_DIR/dist"
JRE_VERSION="21.0.4+7"
JRE_MAJOR="21"
ADOPTIUM_BASE="https://api.adoptium.net/v3/binary/latest/${JRE_MAJOR}/ga"

echo ""
echo "  ╔═══════════════════════════════════════════════════╗"
echo "  ║  TranzFer MFT Client — Bundle Builder             ║"
echo "  ╚═══════════════════════════════════════════════════╝"
echo ""

# --- Step 1: Build the fat JAR ---
echo "  [1/3] Building fat JAR..."
cd "$CLIENT_DIR/.."
if [ -n "$JAVA_HOME" ]; then
  "$JAVA_HOME/bin/java" -version 2>&1 | head -1
  MAVEN_OPTS="-Djava.home=$JAVA_HOME" mvn package -DskipTests -pl mft-client -q 2>/dev/null || \
    mvn package -DskipTests -pl mft-client -q
else
  mvn package -DskipTests -pl mft-client -q
fi
JAR_PATH=$(ls "$CLIENT_DIR/target/mft-client-"*.jar | head -1)
echo "  JAR: $JAR_PATH ($(du -h "$JAR_PATH" | cut -f1))"

# --- Step 2: Download JREs ---
echo ""
echo "  [2/3] Downloading lightweight JREs..."
mkdir -p "$DIST_DIR/jre-cache"

download_jre() {
  local os=$1 arch=$2 ext=$3 label=$4
  local cache_file="$DIST_DIR/jre-cache/jre-${os}-${arch}.${ext}"

  if [ -f "$cache_file" ]; then
    echo "    Cached: $label"
    return
  fi

  local url="${ADOPTIUM_BASE}/${os}/${arch}/jre/hotspot/normal/eclipse"
  echo "    Downloading: $label ..."
  curl -sL -o "$cache_file" "$url"
  echo "    Downloaded: $(du -h "$cache_file" | cut -f1)"
}

download_jre "linux"   "x64"    "tar.gz" "Linux x86_64"
download_jre "linux"   "aarch64" "tar.gz" "Linux ARM64"
download_jre "mac"     "x64"    "tar.gz" "macOS Intel"
download_jre "mac"     "aarch64" "tar.gz" "macOS Apple Silicon"
download_jre "windows" "x64"    "zip"    "Windows x86_64"

# --- Step 3: Create bundles ---
echo ""
echo "  [3/3] Creating platform bundles..."

create_unix_bundle() {
  local os=$1 arch=$2 label=$3 jre_os=$4 jre_arch=$5
  local bundle_name="mft-client-${os}-${arch}"
  local bundle_dir="$DIST_DIR/$bundle_name"
  local cache_file="$DIST_DIR/jre-cache/jre-${jre_os}-${jre_arch}.tar.gz"

  echo "    Building: $label"
  rm -rf "$bundle_dir"
  mkdir -p "$bundle_dir"

  # Extract JRE
  mkdir -p "$bundle_dir/jre"
  tar xzf "$cache_file" -C "$bundle_dir/jre" --strip-components=1 2>/dev/null || \
    tar xzf "$cache_file" -C "$bundle_dir/jre" --strip-components=2 2>/dev/null || true

  # Copy JAR
  cp "$JAR_PATH" "$bundle_dir/mft-client.jar"

  # Copy example config
  cp "$CLIENT_DIR/src/main/resources/mft-client-example.yml" "$bundle_dir/mft-client.yml"

  # Create launcher
  cat > "$bundle_dir/mft-client" << 'LAUNCHER'
#!/usr/bin/env bash
# TranzFer MFT Client launcher (bundled JRE)
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

# Use bundled JRE, fall back to system Java
if [ -x "$DIR/jre/bin/java" ]; then
  JAVA="$DIR/jre/bin/java"
elif command -v java &>/dev/null; then
  JAVA="java"
else
  echo "ERROR: No Java found. The bundled JRE may be corrupt."
  echo "Install Java 21+ or re-download this package."
  exit 1
fi

# Create working directories
mkdir -p outbox inbox sent failed

exec "$JAVA" -Xmx256m -jar "$DIR/mft-client.jar" "$@"
LAUNCHER
  chmod +x "$bundle_dir/mft-client"

  # Create README
  cat > "$bundle_dir/QUICKSTART.txt" << QSEOF
═══════════════════════════════════════════════════════
  TranzFer MFT Client — Quick Start
═══════════════════════════════════════════════════════

  1. Edit mft-client.yml with your server details:
     - server.host = your TranzFer server hostname
     - server.port = 2222 (SFTP) or 21 (FTP)
     - server.username = your account username
     - server.password = your account password

  2. Run:
     ./mft-client         (Linux/macOS)
     mft-client.bat       (Windows)

  3. Drop files into the 'outbox' folder.
     They are automatically transferred to the server.

  4. Check the 'inbox' folder for received files.

═══════════════════════════════════════════════════════
  FIREWALL REQUIREMENTS
═══════════════════════════════════════════════════════

  Your firewall MUST allow OUTBOUND connections to:

  ┌──────────┬────────┬──────────────────────────────┐
  │ Protocol │ Port   │ Purpose                      │
  ├──────────┼────────┼──────────────────────────────┤
  │ TCP      │ 2222   │ SFTP data transfer           │
  │ TCP      │ 22222  │ SFTP (if non-standard port)  │
  │ TCP      │ 21     │ FTP command channel          │
  │ TCP      │ 21000- │ FTP passive data (range      │
  │          │ 21010  │ depends on server config)     │
  │ TCP      │ 443    │ HTTPS (web file portal)      │
  └──────────┴────────┴──────────────────────────────┘

  Ask your TranzFer administrator for the exact
  hostname and port to configure.

  If you are behind a corporate proxy/NAT:
  - SFTP (port 2222) works through most firewalls
  - FTP passive mode requires the passive port range
  - Use SFTP if in doubt — it needs only ONE port

═══════════════════════════════════════════════════════
  TROUBLESHOOTING
═══════════════════════════════════════════════════════

  Connection refused:
    → Check firewall allows outbound to server:port
    → Verify server hostname/IP is correct
    → Try: telnet <host> <port>

  Authentication failed:
    → Verify username and password in mft-client.yml
    → Contact your TranzFer administrator

  Files not uploading:
    → Check outbox/ folder has files
    → Check mft-client.log for errors
    → Verify server path /inbox exists

  Logs: mft-client.log (same directory)

═══════════════════════════════════════════════════════
QSEOF

  # Create firewall doc
  cat > "$bundle_dir/FIREWALL.md" << FWEOF
# Firewall & Network Requirements

## Outbound Rules (Client → Server)

Your network administrator must allow the following **outbound** connections
from this machine to the TranzFer MFT server:

### SFTP (Recommended)
| Direction | Protocol | Port | Destination |
|-----------|----------|------|-------------|
| Outbound  | TCP      | 2222 | MFT Server  |

SFTP requires only **one TCP port**. It works through NAT, proxies,
and most corporate firewalls. **Use SFTP unless you have a specific
reason to use FTP.**

### FTP (Alternative)
| Direction | Protocol | Port Range  | Destination |
|-----------|----------|-------------|-------------|
| Outbound  | TCP      | 21          | MFT Server (command) |
| Outbound  | TCP      | 21000-21010 | MFT Server (passive data) |

FTP requires the command port (21) AND a range of passive data ports.
The exact range depends on your server configuration.

### HTTPS / Web Portal (Optional)
| Direction | Protocol | Port | Destination |
|-----------|----------|------|-------------|
| Outbound  | TCP      | 443  | MFT Server  |
| Outbound  | TCP      | 3001 | MFT File Portal (dev) |

### DNS
Ensure DNS resolution works for the server hostname.

## No Inbound Rules Required

The MFT Client only makes **outbound** connections.
No inbound firewall rules or port forwarding are needed.

## Proxy Support

If you connect through a corporate HTTP proxy:
- SFTP: Most proxies block non-HTTP ports. Ask your network team
  to whitelist the SFTP port, or use the Web Portal instead.
- FTP: Usually blocked by proxies. Use SFTP.
- HTTPS: Works through standard HTTP proxies.

## Testing Connectivity

\`\`\`bash
# Test SFTP port
nc -zv mft.yourcompany.com 2222

# Test FTP port
nc -zv mft.yourcompany.com 21

# Test HTTPS
curl -I https://mft.yourcompany.com
\`\`\`

If these commands time out, contact your network administrator
to open the required ports.
FWEOF

  # Package
  cd "$DIST_DIR"
  tar czf "${bundle_name}.tar.gz" "$bundle_name"
  rm -rf "$bundle_dir"
  echo "    Created: dist/${bundle_name}.tar.gz ($(du -h "${bundle_name}.tar.gz" | cut -f1))"
}

create_windows_bundle() {
  local bundle_name="mft-client-windows-x64"
  local bundle_dir="$DIST_DIR/$bundle_name"
  local cache_file="$DIST_DIR/jre-cache/jre-windows-x64.zip"

  echo "    Building: Windows x86_64"
  rm -rf "$bundle_dir"
  mkdir -p "$bundle_dir"

  # Extract JRE
  mkdir -p "$bundle_dir/jre"
  if command -v unzip &>/dev/null; then
    unzip -q "$cache_file" -d "$bundle_dir/jre-tmp" 2>/dev/null || true
    # Move contents up (strip top-level dir)
    local jre_inner=$(ls "$bundle_dir/jre-tmp/" | head -1)
    if [ -n "$jre_inner" ]; then
      mv "$bundle_dir/jre-tmp/$jre_inner"/* "$bundle_dir/jre/" 2>/dev/null || \
        mv "$bundle_dir/jre-tmp/"* "$bundle_dir/jre/" 2>/dev/null || true
    fi
    rm -rf "$bundle_dir/jre-tmp"
  fi

  # Copy JAR
  cp "$JAR_PATH" "$bundle_dir/mft-client.jar"
  cp "$CLIENT_DIR/src/main/resources/mft-client-example.yml" "$bundle_dir/mft-client.yml"

  # Windows launcher
  cat > "$bundle_dir/mft-client.bat" << 'BATEOF'
@echo off
REM TranzFer MFT Client launcher (bundled JRE)
cd /d "%~dp0"

if exist "%~dp0jre\bin\java.exe" (
    set "JAVA=%~dp0jre\bin\java.exe"
) else (
    where java >/dev/null 2>&1
    if %errorlevel% equ 0 (
        set "JAVA=java"
    ) else (
        echo ERROR: No Java found. Install Java 21+ or re-download this package.
        pause
        exit /b 1
    )
)

if not exist outbox mkdir outbox
if not exist inbox mkdir inbox
if not exist sent mkdir sent
if not exist failed mkdir failed

"%JAVA%" -Xmx256m -jar "%~dp0mft-client.jar" %*
BATEOF

  # Copy docs
  cp "$bundle_dir/../mft-client-linux-x64/QUICKSTART.txt" "$bundle_dir/" 2>/dev/null || \
    echo "See QUICKSTART.txt" > "$bundle_dir/QUICKSTART.txt"

  # Package
  cd "$DIST_DIR"
  if command -v zip &>/dev/null; then
    zip -qr "${bundle_name}.zip" "$bundle_name"
  else
    tar czf "${bundle_name}.tar.gz" "$bundle_name"
  fi
  rm -rf "$bundle_dir"
  local pkg=$(ls "${bundle_name}"* 2>/dev/null | head -1)
  echo "    Created: dist/$pkg ($(du -h "$pkg" | cut -f1))"
}

# Build all platforms
create_unix_bundle "linux" "x64" "Linux x86_64" "linux" "x64"
create_unix_bundle "linux" "arm64" "Linux ARM64" "linux" "aarch64"
create_unix_bundle "macos" "x64" "macOS Intel" "mac" "x64"
create_unix_bundle "macos" "arm64" "macOS Apple Silicon" "mac" "aarch64"
create_windows_bundle

echo ""
echo "  ════════════════════════════════════════════"
echo "  Build complete! Packages in: $DIST_DIR/"
echo "  ════════════════════════════════════════════"
ls -lh "$DIST_DIR/"*.{tar.gz,zip} 2>/dev/null
echo ""
echo "  Distribute the appropriate package to clients."
echo "  They just extract and run — no Java install needed."
echo ""
