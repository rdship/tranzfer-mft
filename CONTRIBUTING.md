# Contributing to TranzFer MFT

Thank you for your interest in contributing!

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/rdship/tranzfer-mft.git`
3. Create a branch: `git checkout -b feature/your-feature`
4. Make your changes
5. Build and test: `mvn clean package`
6. Start locally: `docker compose up --build -d`
7. Submit a pull request

## Development Prerequisites

- Java 21 (Temurin recommended)
- Maven 3.8+
- Docker & Docker Compose
- Node.js 20+ (for UI work)
- PostgreSQL 16 (or use Docker)

## Build

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean package -DskipTests
```

## Code Style

- Java: Standard Spring Boot conventions, Lombok annotations
- React: Functional components with hooks, TailwindCSS utility classes
- Commit messages: Concise, imperative mood ("Add SFTP cipher support")

## Adding a New Microservice

1. Create a new Maven module directory
2. Add `pom.xml` with parent `com.filetransfer:file-transfer-platform`
3. Add the module to root `pom.xml` `<modules>` section
4. Add Spring Boot application class
5. Add Dockerfile (use `eclipse-temurin:21-jre` base)
6. Add to `docker-compose.yml`
7. Add Helm template in `helm/mft-platform/templates/`
8. Add to `values.yaml`

## Reporting Issues

Use GitHub Issues. Include:
- Steps to reproduce
- Expected vs actual behavior
- Service logs (`docker compose logs <service-name>`)
- Environment (OS, Java version, Docker version)

## Contributor License Agreement

By submitting a pull request, you agree to the terms of our Contributor
License Agreement (CLA). See [CLA.md](CLA.md) for full details.

In short: you retain copyright to your contributions, but grant us the
rights to use them in the project under the project's license.

Include this line in your first commit:

    Signed-off-by: Your Name <your-email@example.com>

## License

By contributing, you agree that your contributions will be licensed under
the Business Source License 1.1, the same license that covers the project.
See [LICENSE](LICENSE) for details.
