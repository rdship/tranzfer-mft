# CLI

> Interactive command-line tool for platform administration, installation, and account management.

**Type:** Spring Shell application | **Required:** Optional

---

## Overview

The CLI provides an interactive terminal interface for platform administration:

- **Installation wizard** — Guided server setup with license validation
- **Authentication** — Register and login to the platform
- **Account management** — Create and manage transfer accounts
- **Server configuration** — Configure server instances
- **License management** — Validate and manage licenses
- **DMZ management** — Configure DMZ proxy settings
- **Folder mappings** — Set up file routing rules

---

## Quick Start

```bash
# Build
mvn clean package -pl cli -DskipTests

# Run interactive shell
java -jar cli/target/cli-*.jar

# Or use the installer script
chmod +x installer/tranzfer
./installer/tranzfer install server
```

---

## Commands

### Authentication

| Command | Description |
|---------|-------------|
| `register` | Register a new user account |
| `login` | Login with email and password |
| `whoami` | Show current authentication token |

### Installation

| Command | Description |
|---------|-------------|
| `install server` | Interactive installation wizard |

The installer:
1. Fetches the product catalog from the license service
2. Shows available product tiers (Standard, Professional, Enterprise)
3. Validates your license key
4. Generates a Helm values override (`values-licensed.yaml`)
5. Outputs deployment instructions

### Account Management

| Command | Description |
|---------|-------------|
| `accounts list` | List all transfer accounts |
| `accounts create` | Create a new account (interactive) |
| `accounts enable {id}` | Enable an account |
| `accounts disable {id}` | Disable an account |

### Server Configuration

| Command | Description |
|---------|-------------|
| `servers list` | List server instances |
| `servers create` | Create a new server instance |

### License Management

| Command | Description |
|---------|-------------|
| `license validate` | Validate current license |
| `license info` | Show license details |

### DMZ Configuration

| Command | Description |
|---------|-------------|
| `dmz status` | Show DMZ proxy status |
| `dmz mappings` | List port mappings |

### Folder Mappings

| Command | Description |
|---------|-------------|
| `mappings list` | List folder mappings |
| `mappings create` | Create a new mapping (interactive) |

---

## Configuration

The CLI connects to the platform API. Set the server URL:

```bash
java -jar cli-*.jar --server.url=http://your-server:8080
```

Or use environment variables:
```bash
export MFT_SERVER_URL=http://your-server:8080
java -jar cli-*.jar
```

---

## Build

```bash
mvn clean package -pl cli -DskipTests
```

The output JAR is at `cli/target/cli-*.jar`.

---

## Dependencies

- **onboarding-api** — Account and auth operations
- **license-service** — License validation and product catalog
- **dmz-proxy** — DMZ proxy management
- **config-service** — Server and mapping configuration
