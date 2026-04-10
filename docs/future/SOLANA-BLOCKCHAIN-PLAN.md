# Solana Blockchain Integration Plan for TranzFer MFT

> **Status:** Future Release — Ready-to-Execute  
> **Author:** Roshan Dubey / Claude Code  
> **Created:** 2026-04-10  
> **Target:** TranzFer MFT v2.x  
> **Estimated Effort:** 7 weeks (5 phases)

---

## Table of Contents

1. [Why Solana](#1-why-solana)
2. [Use Cases Across All 22 Microservices](#2-use-cases-across-all-22-microservices)
3. [Technical Architecture](#3-technical-architecture)
4. [Solana Program Design (Rust)](#4-solana-program-design-rust)
5. [Java SDK Integration](#5-java-sdk-integration)
6. [Database Schema Changes](#6-database-schema-changes)
7. [Configuration](#7-configuration)
8. [UI Changes](#8-ui-changes)
9. [Implementation Phases](#9-implementation-phases)
10. [Cost Estimation](#10-cost-estimation)
11. [Security Considerations](#11-security-considerations)
12. [Verification Flow](#12-verification-flow)
13. [Testing Strategy](#13-testing-strategy)
14. [Migration from Current Implementation](#14-migration-from-current-implementation)
15. [Appendix: Reference Links](#15-appendix-reference-links)

---

## 1. Why Solana

### 1.1 The Problem with the Current Implementation

The existing `BlockchainController` (onboarding-api) supports three anchor modes:

- **INTERNAL** — append-only local DB rows. Tamper-proof only if the DB is trusted.
- **DOCUMENT** — RFC 3161 timestamping. Depends on a centralized TSA.
- **ETHEREUM** — placeholder, never implemented.

None of these provide a publicly verifiable, decentralized, cost-effective immutable ledger.
For a managed file transfer platform handling thousands of transfers daily, we need a
blockchain that is fast, cheap, and publicly auditable.

### 1.2 Blockchain Comparison Matrix

| Criterion                  | Solana              | Ethereum            | Hyperledger Fabric   |
|----------------------------|---------------------|---------------------|----------------------|
| **Transaction cost**       | ~$0.00025           | $2-10 (L1)          | $0 (private)         |
| **Finality time**          | 400ms               | 12-15 minutes        | 2-5 seconds          |
| **TPS capacity**           | 65,000+             | ~30 (L1)             | ~3,000               |
| **Public verifiability**   | Yes (public chain)  | Yes (public chain)   | No (permissioned)    |
| **Java SDK**               | solanaj (mature)    | web3j (mature)       | fabric-sdk-java      |
| **Infrastructure cost**    | $0 (use public RPC) | $0 (use public RPC)  | Must run own nodes   |
| **Smart contract lang**    | Rust (Anchor fw)    | Solidity             | Go/Java              |
| **Explorer**               | Solana Explorer     | Etherscan            | Custom only          |
| **Decentralization**       | ~1,900 validators   | ~900,000 validators  | 0 (your nodes only)  |
| **Regulatory acceptance**  | Growing             | Established          | Enterprise preferred  |

### 1.3 Why Solana Wins for MFT

1. **Cost at scale.** At 10,000 transfers/day, Solana costs ~$0.75/month vs Ethereum ~$73,000/month.
   Even with batching (100 transfers per Merkle tree), Ethereum costs ~$730/month vs Solana ~$0.0075/month.

2. **Finality speed.** Solana confirms in 400ms. Our `anchorBatch()` scheduler can run every
   minute instead of every hour. Near-real-time proof-of-delivery becomes viable.

3. **TPS headroom.** Solana handles 65,000+ TPS. Even if TranzFer grows to millions of
   transfers/day, Solana will never be the bottleneck.

4. **Public verifiability.** Any auditor, regulator, or partner can independently verify an
   anchor on Solana Explorer without needing access to our systems.

5. **Java SDK availability.** `solanaj` (com.mmorrell:solanaj) is actively maintained, supports
   transaction building, signing, and custom program interaction.

6. **SPL maturity.** The Solana Program Library provides battle-tested patterns for account
   management, PDA derivation, and program invocation.

7. **No infrastructure.** We use public RPC endpoints (or a dedicated RPC provider like Helius
   or QuickNode for production). Zero blockchain nodes to operate.

### 1.4 Why NOT Ethereum

- **Cost prohibitive.** A single Ethereum L1 transaction costs $2-10. Even with L2 rollups
  (Arbitrum, Optimism), costs are 10-100x higher than Solana.
- **Slow finality.** 12-15 minute confirmation means delayed proof-of-delivery.
- **Gas price volatility.** Ethereum gas prices are unpredictable and can spike 10x during
  network congestion, making cost forecasting impossible.

### 1.5 Why NOT Hyperledger Fabric

- **Not publicly verifiable.** Partners and auditors must be granted network access.
- **Infrastructure burden.** We must run and maintain our own blockchain nodes.
- **Defeats the purpose.** A private blockchain controlled by TranzFer offers no stronger
  guarantees than our existing INTERNAL mode with HMAC-signed audit logs.

---

## 2. Use Cases Across All 22 Microservices

Every service in the TranzFer platform can benefit from blockchain anchoring. The key insight
is that we anchor **hashes and metadata**, never file contents. On-chain data is always a
32-byte SHA-256 hash plus a small metadata struct.

### 2.1 Onboarding API (port 8080)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Transfer proof-of-delivery** | SHA-256 of file + timestamp + sender + receiver track IDs | Non-repudiation: prove file X was delivered from A to B at time T |
| **Partner agreement notarization** | SHA-256 of partner agreement JSON (SLA terms, effective dates) | Immutable record of agreed SLA terms for dispute resolution |
| **Audit log integrity** | Periodic Merkle root of audit_logs table (hourly batches) | Prove audit log was not tampered with after the fact |
| **Auto-onboard session proof** | SHA-256 of session config + partner identity | Prove onboarding parameters were set as claimed |

**Current code to modify:** `BlockchainController.java` — replace ETHEREUM placeholder with
SOLANA mode, inject `SolanaAnchorService`.

### 2.2 Config Service (port 8084)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Flow configuration versioning** | SHA-256 of flow config JSON + version number | Tamper-proof audit trail: prove config was X at time T |
| **SLA breach evidence** | Breach timestamp + metric values + threshold values | Dispute resolution: immutable proof that SLA was breached |
| **Migration lifecycle events** | Migration phase transitions (PENDING -> ACTIVE -> COMPLETED) | Non-repudiable migration audit trail |
| **Scheduled task registration** | Task definition hash + schedule parameters | Prove task was scheduled with specific parameters |

### 2.3 SFTP Service (port 8081)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Session proof** | SSH session fingerprint + client IP + username + timestamp | Prove who connected and when |
| **Key exchange proof** | SHA-256 of host key + client key fingerprint | Prove which cryptographic keys were used |
| **Upload receipt** | File SHA-256 + path + size + session ID | Immutable upload confirmation |

### 2.4 FTP Service (port 8082)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Connection proof** | Client IP + username + TLS version + timestamp | Regulatory proof of connection security level |
| **Transfer receipt** | File SHA-256 + direction (upload/download) + timestamp | Non-repudiation for FTP transfers |

### 2.5 FTP Web Service (port 8083)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Browser upload proof** | File SHA-256 + user agent + IP + timestamp | Prove browser-based transfer occurred |
| **Session attestation** | HTTP session ID hash + user identity | Prove authenticated session |

### 2.6 Gateway Service (port 8085)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Connection routing proof** | Source IP + target service + protocol + listener ID | Prove traffic was routed to correct destination |
| **Protocol negotiation record** | Negotiated protocol version + cipher suite | Regulatory proof of encryption in transit |
| **Listener lifecycle events** | Listener start/stop + configuration hash | Prove gateway was configured correctly at time T |

### 2.7 Encryption Service (port 8086)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Encryption attestation** | File SHA-256 (pre) + algorithm + key ID + file SHA-256 (post) | Prove file was encrypted with specific algorithm at time T |
| **Key rotation proof** | Old key ID + new key ID + rotation timestamp | Prove key rotation occurred per compliance schedule |
| **Decryption authorization** | Requester identity + file SHA-256 + authorization token hash | Prove decryption was authorized |

### 2.8 External Forwarder Service (port 8087)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Forwarding receipt** | Source file SHA-256 + destination endpoint hash + delivery timestamp | Prove file was forwarded to external destination |
| **Retry attestation** | Attempt count + error codes + final status | Prove delivery was attempted N times before failure |
| **Protocol proof** | Destination protocol (SFTP/FTPS/HTTPS) + TLS version | Prove secure protocol was used for external delivery |

### 2.9 DMZ Proxy (port 8088, NO DB)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Security event anchoring** | Blocked IP + reason + rule ID + timestamp | Immutable security event log |
| **Rate limit evidence** | Client IP + request count + window + action taken | Prove rate limiting was enforced |
| **TLS termination proof** | Client cert fingerprint + TLS version + cipher suite | Prove connection security at the edge |

**Note:** DMZ Proxy has no DB. It publishes anchor requests via RabbitMQ to onboarding-api
(or directly to the shared `SolanaAnchorService` via REST).

### 2.10 License Service (port 8089)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **License issuance** | License key hash + tier + features + validity period | Immutable license record, prevents post-hoc disputes |
| **Activation tracking** | License key hash + activation count + instance fingerprint | Prove N activations occurred at specific times |
| **Expiry events** | License key hash + expiry timestamp + grace period used | Prove license expired and when grace period ended |

### 2.11 Analytics Service (port 8090)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Report integrity** | SHA-256 of generated report + time range + parameters | Prove report was generated with these exact parameters |
| **SLA metrics snapshot** | Periodic SLA metric Merkle root (daily rollup) | Immutable SLA metric history for audits |
| **Trend data attestation** | SHA-256 of trend dataset + calculation timestamp | Prove analytics data was not manipulated |

### 2.12 AI Engine (port 8091)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Classification attestation** | File SHA-256 + classification label + confidence + model version | Prove AI classified file as PCI/HIPAA/PII at time T |
| **Anomaly detection evidence** | Anomaly type + severity + detection timestamp + evidence hash | Prove anomaly was detected and alerted |
| **Model version anchoring** | Model SHA-256 + version + training date | Prove which AI model was in use at time T |

### 2.13 Screening Service (port 8092)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Sanctions screening result** | File SHA-256 + screen result (CLEAN/FLAGGED) + engine version | Prove file WAS screened at time T (compliance mandate) |
| **DLP violation evidence** | Violation type + pattern matched + action taken | Immutable record for compliance audits |
| **Quarantine chain-of-custody** | File SHA-256 + quarantine reason + quarantine timestamp + release/delete timestamp | Prove quarantine was enforced end-to-end |
| **Antivirus scan proof** | File SHA-256 + AV engine + signature DB version + result | Prove file was scanned with current signatures |

### 2.14 Keystore Manager (port 8093)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Certificate issuance proof** | Cert fingerprint + subject + issuer + validity period | Prove certificate was issued with specific parameters |
| **Key rotation timeline** | Key ID + algorithm + rotation timestamp + authorized-by | Immutable key lifecycle audit trail |
| **Trust store changes** | Trust store SHA-256 + added/removed cert fingerprints | Prove trust store was modified at time T |

### 2.15 AS2 Service (port 8094)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **MDN receipt anchoring** | MDN hash + AS2 message ID + MIC value | Prove MDN was received (AS2 non-repudiation, RFC 4130) |
| **Partnership proof** | AS2 ID pair + certificate fingerprints + agreement hash | Prove trading partner agreement was in effect |
| **Message integrity** | AS2 message SHA-256 + content type + encryption algo | Prove message content and security at time T |

### 2.16 EDI Converter (port 8095, NO DB)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Conversion attestation** | Input SHA-256 + output SHA-256 + conversion type (X12->JSON etc.) | Prove conversion produced specific output from specific input |
| **Schema validation proof** | Input SHA-256 + schema version + validation result | Prove EDI document was validated against specific schema |

**Note:** EDI Converter has no DB. Anchor requests go via RabbitMQ or REST to
`SolanaAnchorService`.

### 2.17 Storage Manager (port 8096)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Content-addressed storage proof** | File SHA-256 + storage tier + timestamp | Prove file existed in storage at time T |
| **Tier transition record** | File SHA-256 + from-tier + to-tier + transition timestamp | Prove file moved from HOT to COLD at time T |
| **Deduplication proof** | SHA-256 (proves identical content) + dedup reference count | Prove two parties sent identical content without revealing it |
| **Retention compliance** | File SHA-256 + retention policy ID + scheduled-delete date | Prove retention policy was applied correctly |
| **Deletion attestation** | File SHA-256 + deletion timestamp + authorized-by | Prove file was deleted at time T by authorized user |

### 2.18 Notification Service (port 8097)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Delivery proof** | Notification SHA-256 + channel + recipient hash + sent timestamp | Prove notification was sent at time T |
| **Escalation chain** | Alert ID + escalation level + recipients + timestamps | Prove escalation procedure was followed |

### 2.19 Platform Sentinel (port 8098)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Security finding evidence** | Finding type + severity + affected service + timestamp | Immutable security event record |
| **Health score history** | Composite health score + per-service scores + timestamp | Tamper-proof health timeline for SLA disputes |
| **Rule evaluation proof** | Rule ID + input hash + result + evaluation timestamp | Prove security rule was evaluated with specific inputs |
| **Compliance snapshot** | Full compliance status Merkle root (daily) | Prove platform compliance posture at time T |

### 2.20 API Gateway (port — reverse proxy)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Route configuration proof** | Route table SHA-256 + version + timestamp | Prove routing was configured correctly |

### 2.21 UI Service + Partner Portal + CLI

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **Administrative action proof** | Action type + user identity hash + target resource + timestamp | Prove admin actions were performed by authorized users |
| **CLI command attestation** | Command hash + user identity + execution timestamp | Audit trail for CLI-based operations |

### 2.22 Shared Platform (cross-cutting)

| Use Case | What Gets Anchored | Business Value |
|----------|-------------------|----------------|
| **SPIFFE identity rotation** | SVID fingerprint + SPIFFE ID + rotation timestamp | Prove service identity was valid at time T |
| **RabbitMQ event integrity** | Periodic Merkle root of event hashes (hourly) | Prove event stream was not tampered with |
| **Database migration proof** | Flyway migration SHA-256 + version + execution timestamp | Prove schema migration was executed correctly |

---

## 3. Technical Architecture

### 3.1 System Diagram

```
+------------------------------------------------------------------+
|                     Solana Blockchain                              |
|  Program: TranzFerAnchor (deployed custom Solana program)        |
|  +------------------------------------------------------------+  |
|  | Instructions:                                                |  |
|  |   anchor_single(merkle_root, metadata_hash, anchor_type)   |  |
|  |   anchor_batch(merkle_roots[], metadata_hashes[])          |  |
|  |   verify_anchor(merkle_root) -> AnchorAccount              |  |
|  +------------------------------------------------------------+  |
|  | Accounts (PDAs):                                             |  |
|  |   AnchorAccount { merkle_root, metadata, ts, signer, type }|  |
|  |   BatchAccount  { batch_id, count, merkle_root_of_roots }  |  |
|  |   StatsAccount  { total_anchors, total_batches, sol_spent } |  |
|  +------------------------------------------------------------+  |
+----------------------------+-------------------------------------+
                             | JSON-RPC over HTTPS
                             | (Helius / QuickNode / public RPC)
                             |
+----------------------------+-------------------------------------+
|              SolanaAnchorService (shared-platform)                |
|  Location: shared/shared-platform/.../service/                   |
|  +------------------------------------------------------------+  |
|  | SolanaAnchorService.java                                    |  |
|  |   - anchorTransfer(trackId, sha256, metadata)              |  |
|  |   - anchorAudit(auditMerkleRoot)                           |  |
|  |   - anchorScreening(fileHash, result, engineVersion)       |  |
|  |   - anchorGeneric(type, hash, metadata)                    |  |
|  |   - verifyAnchor(merkleRoot) -> SolanaVerification         |  |
|  +------------------------------------------------------------+  |
|  | SolanaBatchQueue.java                                       |  |
|  |   - Collects anchors for configurable interval (default 60s)|  |
|  |   - Computes batch Merkle root of individual roots          |  |
|  |   - Submits single on-chain transaction per batch           |  |
|  |   - Graceful degradation: if Solana unreachable, persists  |  |
|  |     to blockchain_batch_queue table for retry               |  |
|  +------------------------------------------------------------+  |
|  | SolanaClient.java (wraps solanaj)                           |  |
|  |   - Connection management, retry, circuit breaker           |  |
|  |   - Transaction building + signing                          |  |
|  |   - Slot/block time retrieval                               |  |
|  +------------------------------------------------------------+  |
+----------------------------+-------------------------------------+
                             |
          +------------------+-------------------+
          |                  |                   |
  onboarding-api     storage-manager     screening-service
  (transfer proof)   (content proof)     (screening attestation)
          |                  |                   |
  config-service     encryption-svc      ai-engine
  (config version)   (key rotation)      (classification)
          |                  |                   |
  ... all 22 services inject SolanaAnchorService from shared-platform
```

### 3.2 Data Flow

```
1. Service Event Occurs (e.g., file transfer completes)
        |
2. Service calls SolanaAnchorService.anchorTransfer(trackId, sha256, metadata)
        |
3. SolanaAnchorService creates AnchorRequest{type, hash, metadata, timestamp}
        |
4. AnchorRequest enqueued in SolanaBatchQueue (in-memory + DB backup)
        |
5. Every 60 seconds, SolanaBatchQueue:
   a. Collects all pending AnchorRequests
   b. Computes Merkle root of all request hashes
   c. Builds Solana transaction: anchor_batch instruction
   d. Signs with platform wallet keypair
   e. Submits to Solana RPC
        |
6. On confirmation (400ms):
   a. Updates blockchain_anchors rows with solana_signature, slot, block_time
   b. Sets status = CONFIRMED
        |
7. On finalization (~6.4 seconds):
   a. Sets status = FINALIZED
   b. Generates Solana Explorer verification URL
```

### 3.3 Graceful Degradation

```
Normal:    Service -> SolanaAnchorService -> Solana RPC -> Confirmed
                                                  |
Degraded:  Solana RPC unreachable                 |
           SolanaAnchorService persists to ----> blockchain_batch_queue (DB)
           Retry scheduler picks up every 5 min
           On recovery: submit + backfill confirmation data
                                                  |
Disabled:  solana.enabled=false                   |
           SolanaAnchorService becomes no-op, logs warning
           Existing INTERNAL mode continues working
```

### 3.4 Async, Never Blocking

The anchor service is fully asynchronous. No file transfer is ever delayed or blocked by
blockchain operations.

```java
// In any service — fire and forget
solanaAnchorService.anchorTransfer(trackId, sha256, metadata);  // returns void, async
```

If Solana is slow, down, or disabled, file transfers continue at full speed. Anchoring is a
background enrichment, not a critical path dependency.

---

## 4. Solana Program Design (Rust)

### 4.1 Program Overview

We use the **Anchor framework** (not to be confused with our blockchain anchors) — the
standard Rust framework for Solana program development. It provides account serialization,
instruction dispatch, CPI helpers, and testing utilities.

### 4.2 Account Structures

```rust
// programs/tranzfer_anchor/src/lib.rs

use anchor_lang::prelude::*;

declare_id!("TRZFxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");

#[program]
pub mod tranzfer_anchor {
    use super::*;

    /// Anchor a single Merkle root on-chain.
    pub fn anchor_single(
        ctx: Context<AnchorSingle>,
        merkle_root: [u8; 32],
        metadata_hash: [u8; 32],
        anchor_type: AnchorType,
    ) -> Result<()> {
        let anchor_account = &mut ctx.accounts.anchor_account;
        anchor_account.merkle_root = merkle_root;
        anchor_account.metadata_hash = metadata_hash;
        anchor_account.anchor_type = anchor_type;
        anchor_account.signer = ctx.accounts.signer.key();
        anchor_account.timestamp = Clock::get()?.unix_timestamp;
        anchor_account.slot = Clock::get()?.slot;
        anchor_account.bump = ctx.bumps.anchor_account;

        // Update global stats
        let stats = &mut ctx.accounts.stats_account;
        stats.total_anchors += 1;

        emit!(AnchorEvent {
            merkle_root,
            anchor_type,
            timestamp: anchor_account.timestamp,
            signer: anchor_account.signer,
        });

        Ok(())
    }

    /// Anchor a batch of Merkle roots in a single transaction.
    /// Stores the Merkle root of all individual roots (root-of-roots).
    pub fn anchor_batch(
        ctx: Context<AnchorBatch>,
        batch_merkle_root: [u8; 32],
        count: u32,
        anchor_types: Vec<AnchorType>,
    ) -> Result<()> {
        let batch_account = &mut ctx.accounts.batch_account;
        batch_account.batch_merkle_root = batch_merkle_root;
        batch_account.count = count;
        batch_account.signer = ctx.accounts.signer.key();
        batch_account.timestamp = Clock::get()?.unix_timestamp;
        batch_account.slot = Clock::get()?.slot;
        batch_account.bump = ctx.bumps.batch_account;

        // Update global stats
        let stats = &mut ctx.accounts.stats_account;
        stats.total_batches += 1;
        stats.total_anchors += count as u64;

        emit!(BatchAnchorEvent {
            batch_merkle_root,
            count,
            timestamp: batch_account.timestamp,
            signer: batch_account.signer,
        });

        Ok(())
    }
}
```

### 4.3 Data Schemas

```rust
/// Individual anchor account. PDA seed: ["anchor", merkle_root].
#[account]
pub struct AnchorAccount {
    pub merkle_root: [u8; 32],     // 32 bytes — SHA-256 Merkle root
    pub metadata_hash: [u8; 32],   // 32 bytes — SHA-256 of JSON metadata
    pub anchor_type: AnchorType,   // 1 byte  — enum discriminator
    pub signer: Pubkey,            // 32 bytes — signing wallet
    pub timestamp: i64,            // 8 bytes  — on-chain Unix timestamp
    pub slot: u64,                 // 8 bytes  — Solana slot number
    pub bump: u8,                  // 1 byte   — PDA bump seed
}
// Total: 32 + 32 + 1 + 32 + 8 + 8 + 1 = 114 bytes + 8 (discriminator) = 122 bytes

/// Batch anchor account. PDA seed: ["batch", batch_merkle_root].
#[account]
pub struct BatchAccount {
    pub batch_merkle_root: [u8; 32],  // 32 bytes — root of all Merkle roots in batch
    pub count: u32,                    // 4 bytes  — number of individual anchors
    pub signer: Pubkey,                // 32 bytes
    pub timestamp: i64,                // 8 bytes
    pub slot: u64,                     // 8 bytes
    pub bump: u8,                      // 1 byte
}
// Total: 32 + 4 + 32 + 8 + 8 + 1 = 85 bytes + 8 (discriminator) = 93 bytes

/// Global stats account (singleton). PDA seed: ["stats"].
#[account]
pub struct StatsAccount {
    pub total_anchors: u64,    // 8 bytes
    pub total_batches: u64,    // 8 bytes
    pub authority: Pubkey,     // 32 bytes — program upgrade authority
    pub bump: u8,              // 1 byte
}
// Total: 8 + 8 + 32 + 1 = 49 bytes + 8 (discriminator) = 57 bytes

/// Anchor type enum — determines which service category created this anchor.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq)]
pub enum AnchorType {
    TransferProof,        // 0 — file transfer proof-of-delivery
    AuditIntegrity,       // 1 — audit log Merkle root
    ScreeningAttestation, // 2 — sanctions/DLP screening result
    ConfigVersion,        // 3 — flow/config versioning
    EncryptionProof,      // 4 — encryption/key rotation attestation
    LicenseRecord,        // 5 — license issuance/activation
    ClassificationProof,  // 6 — AI classification attestation
    SecurityEvent,        // 7 — sentinel/DMZ security events
    StorageProof,         // 8 — content-addressed storage proof
    DeliveryProof,        // 9 — notification delivery proof
    IdentityProof,        // 10 — SPIFFE identity rotation
    ProtocolProof,        // 11 — AS2 MDN, gateway connection
    ConversionProof,      // 12 — EDI conversion attestation
}
```

### 4.4 Account Contexts (Anchor Framework)

```rust
#[derive(Accounts)]
#[instruction(merkle_root: [u8; 32])]
pub struct AnchorSingle<'info> {
    #[account(
        init,
        payer = signer,
        space = 8 + 122,
        seeds = [b"anchor", merkle_root.as_ref()],
        bump
    )]
    pub anchor_account: Account<'info, AnchorAccount>,

    #[account(
        mut,
        seeds = [b"stats"],
        bump = stats_account.bump
    )]
    pub stats_account: Account<'info, StatsAccount>,

    #[account(mut)]
    pub signer: Signer<'info>,

    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(batch_merkle_root: [u8; 32])]
pub struct AnchorBatch<'info> {
    #[account(
        init,
        payer = signer,
        space = 8 + 93,
        seeds = [b"batch", batch_merkle_root.as_ref()],
        bump
    )]
    pub batch_account: Account<'info, BatchAccount>,

    #[account(
        mut,
        seeds = [b"stats"],
        bump = stats_account.bump
    )]
    pub stats_account: Account<'info, StatsAccount>,

    #[account(mut)]
    pub signer: Signer<'info>,

    pub system_program: Program<'info, System>,
}
```

### 4.5 Events (for off-chain indexing)

```rust
#[event]
pub struct AnchorEvent {
    pub merkle_root: [u8; 32],
    pub anchor_type: AnchorType,
    pub timestamp: i64,
    pub signer: Pubkey,
}

#[event]
pub struct BatchAnchorEvent {
    pub batch_merkle_root: [u8; 32],
    pub count: u32,
    pub timestamp: i64,
    pub signer: Pubkey,
}
```

### 4.6 Account Rent Costs

Solana requires a minimum balance (rent exemption) to keep accounts alive:

| Account Type   | Size (bytes) | Rent-exempt minimum (SOL) | USD (~$150/SOL) |
|----------------|-------------|---------------------------|-----------------|
| AnchorAccount  | 122 + 8     | ~0.00161 SOL              | ~$0.24          |
| BatchAccount   | 93 + 8      | ~0.00134 SOL              | ~$0.20          |
| StatsAccount   | 57 + 8      | ~0.00110 SOL              | ~$0.17          |

**Optimization: batch-only mode.** If we only create BatchAccount PDAs (one per batch of 100
transfers), we create ~100-1000 accounts/day instead of 10,000-100,000. This drastically
reduces rent costs. Individual transfer proofs are verified via the off-chain Merkle tree
stored in our DB, with the batch Merkle root verified on-chain.

**Recommended approach:** Use `anchor_batch` exclusively. Store individual Merkle proofs in
`blockchain_anchors` table. On-chain, only the batch root-of-roots exists. Verification:
DB proof -> batch Merkle root -> on-chain BatchAccount.

### 4.7 Program Deployment

```bash
# 1. Install Solana CLI + Anchor
sh -c "$(curl -sSfL https://release.anza.xyz/stable/install)"
cargo install --git https://github.com/coral-xyz/anchor avm --force
avm install latest && avm use latest

# 2. Build
cd solana-program/
anchor build

# 3. Deploy to devnet (testing)
solana config set --url https://api.devnet.solana.com
solana airdrop 2  # free devnet SOL
anchor deploy --provider.cluster devnet

# 4. Deploy to mainnet (production)
solana config set --url https://api.mainnet-beta.solana.com
# Ensure wallet has sufficient SOL for deployment (~3-5 SOL)
anchor deploy --provider.cluster mainnet
```

---

## 5. Java SDK Integration

### 5.1 Library Selection

**solanaj** (`com.mmorrell:solanaj`) — the most mature Java SDK for Solana:
- Active development, used in production by multiple projects
- Supports transaction building, signing, RPC calls, and custom program interaction
- Lightweight, no heavy dependencies

### 5.2 Maven Dependency

```xml
<!-- In shared/shared-platform/pom.xml -->
<dependency>
    <groupId>com.mmorrell</groupId>
    <artifactId>solanaj</artifactId>
    <version>1.19.2</version>
</dependency>
```

### 5.3 SolanaClient.java (Low-Level Wrapper)

```java
package com.filetransfer.shared.blockchain;

import com.mmorrell.solanaj.core.*;
import com.mmorrell.solanaj.rpc.RpcClient;
import com.mmorrell.solanaj.rpc.RpcException;
import com.mmorrell.solanaj.rpc.types.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component @Slf4j
public class SolanaClient {

    @Value("${solana.rpc-url:https://api.devnet.solana.com}")
    private String rpcUrl;

    @Value("${solana.wallet-path:/etc/tranzfer/solana-wallet.json}")
    private String walletPath;

    @Value("${solana.program-id:}")
    private String programId;

    @Value("${solana.enabled:false}")
    private boolean enabled;

    private RpcClient rpcClient;
    private Account signerAccount;
    private PublicKey programPublicKey;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("Solana integration disabled (solana.enabled=false)");
            return;
        }
        try {
            rpcClient = new RpcClient(rpcUrl);
            signerAccount = loadWallet(walletPath);
            programPublicKey = new PublicKey(programId);
            log.info("Solana client initialized: rpc={}, program={}, wallet={}",
                rpcUrl, programId, signerAccount.getPublicKey().toBase58());
        } catch (Exception e) {
            log.error("Failed to initialize Solana client: {}", e.getMessage());
            enabled = false;
        }
    }

    /**
     * Submit an anchor_batch instruction to the TranzFer Solana program.
     * Returns the transaction signature on success, null on failure.
     */
    public String submitBatchAnchor(byte[] batchMerkleRoot, int count, List<Integer> anchorTypes) {
        if (!enabled) return null;
        try {
            // Derive PDA for batch account: seeds = ["batch", batchMerkleRoot]
            PublicKey batchPda = PublicKey.findProgramAddress(
                List.of("batch".getBytes(), batchMerkleRoot),
                programPublicKey
            ).getAddress();

            // Derive PDA for stats account: seeds = ["stats"]
            PublicKey statsPda = PublicKey.findProgramAddress(
                List.of("stats".getBytes()),
                programPublicKey
            ).getAddress();

            // Build instruction data: discriminator(8) + batchMerkleRoot(32) + count(4) + types(vec)
            byte[] instructionData = buildBatchInstructionData(batchMerkleRoot, count, anchorTypes);

            // Build transaction
            AccountMeta batchAccountMeta = new AccountMeta(batchPda, true, true);
            AccountMeta statsAccountMeta = new AccountMeta(statsPda, true, false);
            AccountMeta signerMeta = new AccountMeta(signerAccount.getPublicKey(), true, true);
            AccountMeta systemProgram = new AccountMeta(
                PublicKey.valueOf("11111111111111111111111111111111"), false, false);

            TransactionInstruction instruction = new TransactionInstruction(
                programPublicKey,
                List.of(batchAccountMeta, statsAccountMeta, signerMeta, systemProgram),
                instructionData
            );

            Transaction tx = new Transaction();
            tx.addInstruction(instruction);

            String signature = rpcClient.getApi().sendTransaction(tx, signerAccount);
            log.info("Solana batch anchor submitted: sig={}, count={}", signature, count);
            return signature;

        } catch (RpcException e) {
            log.error("Solana RPC error submitting batch anchor: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to submit Solana batch anchor: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Verify an anchor exists on-chain by fetching the PDA account.
     */
    public SolanaVerification verifyAnchor(byte[] merkleRoot, boolean isBatch) {
        if (!enabled) return SolanaVerification.disabled();
        try {
            String seedPrefix = isBatch ? "batch" : "anchor";
            PublicKey pda = PublicKey.findProgramAddress(
                List.of(seedPrefix.getBytes(), merkleRoot),
                programPublicKey
            ).getAddress();

            AccountInfo accountInfo = rpcClient.getApi().getAccountInfo(pda);
            if (accountInfo == null || accountInfo.getValue() == null) {
                return SolanaVerification.notFound(merkleRoot);
            }

            // Parse account data to extract timestamp, signer, slot
            byte[] data = accountInfo.getValue().getData();
            return SolanaVerification.fromAccountData(data, pda.toBase58());

        } catch (Exception e) {
            log.warn("Solana verification failed for merkle root: {}", e.getMessage());
            return SolanaVerification.error(e.getMessage());
        }
    }

    /** Get current wallet SOL balance. */
    public double getBalance() {
        if (!enabled) return 0.0;
        try {
            long lamports = rpcClient.getApi().getBalance(signerAccount.getPublicKey());
            return lamports / 1_000_000_000.0;
        } catch (Exception e) {
            log.warn("Failed to get Solana wallet balance: {}", e.getMessage());
            return -1.0;
        }
    }

    /** Get the current Solana slot (block height). */
    public long getCurrentSlot() {
        if (!enabled) return -1;
        try {
            return rpcClient.getApi().getSlot();
        } catch (Exception e) { return -1; }
    }

    public boolean isEnabled() { return enabled; }
    public String getNetwork() { return rpcUrl; }
    public String getWalletAddress() {
        return signerAccount != null ? signerAccount.getPublicKey().toBase58() : "N/A";
    }

    private Account loadWallet(String path) throws IOException {
        byte[] keyBytes = Files.readAllBytes(Path.of(path));
        // Solana wallet is a JSON array of 64 bytes (secret key)
        return Account.fromJson(new String(keyBytes));
    }

    private byte[] buildBatchInstructionData(byte[] merkleRoot, int count, List<Integer> types) {
        // Anchor discriminator for "anchor_batch" = first 8 bytes of SHA-256("global:anchor_batch")
        byte[] discriminator = new byte[]{/* computed at build time */};
        // Serialize: discriminator(8) + merkleRoot(32) + count(4) + types_len(4) + types(n)
        // Implementation uses Borsh serialization matching the Rust program
        // ... (full Borsh serializer in SolanaBorshSerializer.java)
        return new byte[0]; // placeholder — see SolanaBorshSerializer
    }
}
```

### 5.4 SolanaAnchorService.java (High-Level Service)

```java
package com.filetransfer.shared.blockchain;

import com.filetransfer.shared.entity.BlockchainAnchor;
import com.filetransfer.shared.repository.BlockchainAnchorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service @RequiredArgsConstructor @Slf4j
public class SolanaAnchorService {

    private final SolanaClient solanaClient;
    private final BlockchainAnchorRepository anchorRepo;
    private final BlockchainBatchQueueRepository batchQueueRepo;

    @Value("${solana.enabled:false}")
    private boolean enabled;

    @Value("${solana.max-batch-size:100}")
    private int maxBatchSize;

    /** In-memory queue for anchor requests. Drained on batch interval. */
    private final ConcurrentLinkedQueue<AnchorRequest> pendingQueue = new ConcurrentLinkedQueue<>();

    // ── Public API: called by any service ──────────────────────────

    /** Anchor a file transfer (async, non-blocking). */
    @Async
    public void anchorTransfer(String trackId, String sha256, Map<String, String> metadata) {
        enqueue(AnchorType.TRANSFER_PROOF, trackId, sha256, metadata);
    }

    /** Anchor audit log integrity (async, non-blocking). */
    @Async
    public void anchorAudit(String auditMerkleRoot, Map<String, String> metadata) {
        enqueue(AnchorType.AUDIT_INTEGRITY, null, auditMerkleRoot, metadata);
    }

    /** Anchor screening attestation (async, non-blocking). */
    @Async
    public void anchorScreening(String fileHash, String result, String engineVersion) {
        Map<String, String> meta = Map.of("result", result, "engine", engineVersion);
        enqueue(AnchorType.SCREENING_ATTESTATION, null, fileHash, meta);
    }

    /** Anchor config version (async, non-blocking). */
    @Async
    public void anchorConfig(String configHash, String version, String flowId) {
        Map<String, String> meta = Map.of("version", version, "flowId", flowId);
        enqueue(AnchorType.CONFIG_VERSION, null, configHash, meta);
    }

    /** Anchor encryption proof (async, non-blocking). */
    @Async
    public void anchorEncryption(String fileHash, String algorithm, String keyId) {
        Map<String, String> meta = Map.of("algorithm", algorithm, "keyId", keyId);
        enqueue(AnchorType.ENCRYPTION_PROOF, null, fileHash, meta);
    }

    /** Generic anchor for any service (async, non-blocking). */
    @Async
    public void anchorGeneric(AnchorType type, String hash, Map<String, String> metadata) {
        enqueue(type, null, hash, metadata);
    }

    // ── Batch Processing ───────────────────────────────────────────

    /**
     * Drain the pending queue and submit a batch to Solana.
     * Runs on configured interval (default: every 60 seconds).
     */
    @Scheduled(fixedDelayString = "${solana.batch-interval-seconds:60}000")
    public void processBatch() {
        if (!enabled || pendingQueue.isEmpty()) return;

        List<AnchorRequest> batch = new ArrayList<>();
        while (!pendingQueue.isEmpty() && batch.size() < maxBatchSize) {
            AnchorRequest req = pendingQueue.poll();
            if (req != null) batch.add(req);
        }
        if (batch.isEmpty()) return;

        // Compute batch Merkle root (root of all individual hashes)
        List<String> hashes = batch.stream().map(AnchorRequest::getHash).toList();
        String batchMerkleRoot = computeMerkleRoot(hashes);
        byte[] merkleRootBytes = hexToBytes(batchMerkleRoot);

        // Collect anchor types
        List<Integer> types = batch.stream()
            .map(r -> r.getType().ordinal())
            .toList();

        // Submit to Solana
        String signature = solanaClient.submitBatchAnchor(merkleRootBytes, batch.size(), types);

        if (signature != null) {
            // Success: update all anchors with Solana data
            long slot = solanaClient.getCurrentSlot();
            for (AnchorRequest req : batch) {
                updateAnchorWithSolanaData(req, signature, slot, batchMerkleRoot);
            }
            log.info("Solana batch submitted: sig={}, count={}, merkle={}",
                signature, batch.size(), batchMerkleRoot.substring(0, 16));
        } else {
            // Failure: persist to batch queue for retry
            for (AnchorRequest req : batch) {
                batchQueueRepo.save(BlockchainBatchQueue.builder()
                    .hash(req.getHash())
                    .anchorType(req.getType().name())
                    .metadata(req.getMetadataJson())
                    .trackId(req.getTrackId())
                    .status("PENDING")
                    .createdAt(Instant.now())
                    .build());
            }
            log.warn("Solana batch submission failed, {} items queued for retry", batch.size());
        }
    }

    /**
     * Retry failed batches from the database queue.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300_000)
    public void retryFailedBatches() {
        if (!enabled) return;
        List<BlockchainBatchQueue> pending = batchQueueRepo.findByStatus("PENDING");
        if (pending.isEmpty()) return;

        // Re-enqueue for next batch cycle
        for (BlockchainBatchQueue item : pending) {
            pendingQueue.add(AnchorRequest.fromQueueItem(item));
            item.setStatus("RETRYING");
            batchQueueRepo.save(item);
        }
        log.info("Re-enqueued {} failed anchor requests for retry", pending.size());
    }

    // ── Verification ───────────────────────────────────────────────

    /** Verify an anchor on Solana. Returns verification details. */
    public SolanaVerification verify(String merkleRoot) {
        return solanaClient.verifyAnchor(hexToBytes(merkleRoot), true);
    }

    // ── Internal ───────────────────────────────────────────────────

    private void enqueue(AnchorType type, String trackId, String hash, Map<String, String> metadata) {
        if (!enabled) return;
        pendingQueue.add(new AnchorRequest(type, trackId, hash, metadata, Instant.now()));
    }

    private void updateAnchorWithSolanaData(
            AnchorRequest req, String signature, long slot, String batchMerkleRoot) {
        if (req.getTrackId() != null) {
            anchorRepo.findByTrackId(req.getTrackId()).ifPresent(anchor -> {
                anchor.setTxHash(signature);
                anchor.setBlockNumber(slot);
                anchor.setChain("SOLANA");
                anchor.setMerkleRoot(batchMerkleRoot);
                anchor.setProof(anchor.getProof() + ";solana_sig=" + signature
                    + ";slot=" + slot + ";batch_root=" + batchMerkleRoot);
                anchorRepo.save(anchor);
            });
        }
    }

    private String computeMerkleRoot(List<String> hashes) {
        if (hashes.isEmpty()) return "";
        List<String> layer = new ArrayList<>(hashes);
        while (layer.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < layer.size(); i += 2) {
                String left = layer.get(i);
                String right = i + 1 < layer.size() ? layer.get(i + 1) : left;
                next.add(sha256(left + right));
            }
            layer = next;
        }
        return layer.get(0);
    }

    private String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) { return input; }
    }

    private byte[] hexToBytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
```

### 5.5 Supporting Types

```java
// AnchorRequest.java
package com.filetransfer.shared.blockchain;

import lombok.*;
import java.time.Instant;
import java.util.Map;

@Getter @AllArgsConstructor
public class AnchorRequest {
    private final AnchorType type;
    private final String trackId;  // nullable — only for transfer proofs
    private final String hash;     // SHA-256 hex string
    private final Map<String, String> metadata;
    private final Instant createdAt;

    public String getMetadataJson() {
        // Simple JSON serialization of metadata map
        StringBuilder sb = new StringBuilder("{");
        metadata.forEach((k, v) -> sb.append("\"").append(k).append("\":\"").append(v).append("\","));
        if (!metadata.isEmpty()) sb.setLength(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    public static AnchorRequest fromQueueItem(BlockchainBatchQueue item) {
        return new AnchorRequest(
            AnchorType.valueOf(item.getAnchorType()),
            item.getTrackId(),
            item.getHash(),
            Map.of(),  // metadata not critical for retry
            item.getCreatedAt()
        );
    }
}
```

```java
// AnchorType.java
package com.filetransfer.shared.blockchain;

public enum AnchorType {
    TRANSFER_PROOF,
    AUDIT_INTEGRITY,
    SCREENING_ATTESTATION,
    CONFIG_VERSION,
    ENCRYPTION_PROOF,
    LICENSE_RECORD,
    CLASSIFICATION_PROOF,
    SECURITY_EVENT,
    STORAGE_PROOF,
    DELIVERY_PROOF,
    IDENTITY_PROOF,
    PROTOCOL_PROOF,
    CONVERSION_PROOF
}
```

```java
// SolanaVerification.java
package com.filetransfer.shared.blockchain;

import lombok.*;

@Getter @Builder
public class SolanaVerification {
    private final boolean verified;
    private final boolean onChain;
    private final String merkleRoot;
    private final String solanaSignature;
    private final long slot;
    private final long timestamp;
    private final String signerAddress;
    private final String pdaAddress;
    private final String explorerUrl;
    private final String error;

    public static SolanaVerification disabled() {
        return SolanaVerification.builder().verified(false).error("Solana integration disabled").build();
    }

    public static SolanaVerification notFound(byte[] merkleRoot) {
        return SolanaVerification.builder().verified(false).onChain(false)
            .merkleRoot(HexFormat.of().formatHex(merkleRoot))
            .error("Anchor not found on Solana").build();
    }

    public static SolanaVerification error(String msg) {
        return SolanaVerification.builder().verified(false).error(msg).build();
    }

    public static SolanaVerification fromAccountData(byte[] data, String pdaAddress) {
        // Parse Borsh-serialized account data
        // Skip 8-byte discriminator, read fields in order
        // ... (full parser implementation)
        return SolanaVerification.builder()
            .verified(true).onChain(true).pdaAddress(pdaAddress)
            .build();
    }
}
```

---

## 6. Database Schema Changes

### 6.1 Alter `blockchain_anchors` Table

Flyway migration: `V47__solana_blockchain_columns.sql`
(Numbered above shared-platform JAR range per project convention.)

```sql
-- V47__solana_blockchain_columns.sql
-- Add Solana-specific columns to blockchain_anchors

ALTER TABLE blockchain_anchors ADD COLUMN IF NOT EXISTS solana_signature VARCHAR(128);
ALTER TABLE blockchain_anchors ADD COLUMN IF NOT EXISTS solana_slot BIGINT;
ALTER TABLE blockchain_anchors ADD COLUMN IF NOT EXISTS solana_block_time BIGINT;
ALTER TABLE blockchain_anchors ADD COLUMN IF NOT EXISTS anchor_type VARCHAR(32) DEFAULT 'TRANSFER_PROOF';
ALTER TABLE blockchain_anchors ADD COLUMN IF NOT EXISTS anchor_status VARCHAR(16) DEFAULT 'PENDING';
ALTER TABLE blockchain_anchors ADD COLUMN IF NOT EXISTS verification_url VARCHAR(512);
ALTER TABLE blockchain_anchors ADD COLUMN IF NOT EXISTS batch_merkle_root VARCHAR(64);
ALTER TABLE blockchain_anchors ADD COLUMN IF NOT EXISTS metadata_hash VARCHAR(64);

-- Index for Solana signature lookups
CREATE INDEX IF NOT EXISTS idx_bc_solana_sig ON blockchain_anchors(solana_signature);

-- Index for anchor type filtering
CREATE INDEX IF NOT EXISTS idx_bc_anchor_type ON blockchain_anchors(anchor_type);

-- Index for status queries (batch processing)
CREATE INDEX IF NOT EXISTS idx_bc_anchor_status ON blockchain_anchors(anchor_status);

-- Index for batch Merkle root lookups
CREATE INDEX IF NOT EXISTS idx_bc_batch_root ON blockchain_anchors(batch_merkle_root);
```

### 6.2 New Table: `blockchain_batch_queue`

```sql
-- V48__blockchain_batch_queue.sql
-- Queue table for batching anchors before Solana submission

CREATE TABLE IF NOT EXISTS blockchain_batch_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id VARCHAR(12),
    hash VARCHAR(64) NOT NULL,
    anchor_type VARCHAR(32) NOT NULL,
    metadata TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    submitted_at TIMESTAMP WITH TIME ZONE,
    solana_signature VARCHAR(128),
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_bbq_status ON blockchain_batch_queue(status);
CREATE INDEX IF NOT EXISTS idx_bbq_created ON blockchain_batch_queue(created_at);
```

### 6.3 Updated Entity

```java
// Updated BlockchainAnchor.java — new fields
@Entity @Table(name = "blockchain_anchors", indexes = {
    @Index(name = "idx_bc_track", columnList = "trackId"),
    @Index(name = "idx_bc_solana_sig", columnList = "solanaSignature"),
    @Index(name = "idx_bc_anchor_type", columnList = "anchorType"),
    @Index(name = "idx_bc_anchor_status", columnList = "anchorStatus"),
    @Index(name = "idx_bc_batch_root", columnList = "batchMerkleRoot")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BlockchainAnchor {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 12) private String trackId;
    @Column(nullable = false) private String filename;
    @Column(nullable = false, length = 64) private String sha256;
    @Column(length = 64) private String merkleRoot;
    @Builder.Default private String chain = "INTERNAL";
    private String txHash;
    private Long blockNumber;
    @Column(columnDefinition = "TEXT") private String proof;
    @Column(nullable = false, updatable = false) @Builder.Default private Instant anchoredAt = Instant.now();

    // ── New Solana fields ──
    @Column(length = 128) private String solanaSignature;
    private Long solanaSlot;
    private Long solanaBlockTime;
    @Column(length = 32) @Builder.Default private String anchorType = "TRANSFER_PROOF";
    @Column(length = 16) @Builder.Default private String anchorStatus = "PENDING";
    @Column(length = 512) private String verificationUrl;
    @Column(length = 64) private String batchMerkleRoot;
    @Column(length = 64) private String metadataHash;
}
```

### 6.4 New Entity: BlockchainBatchQueue

```java
package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "blockchain_batch_queue", indexes = {
    @Index(name = "idx_bbq_status", columnList = "status"),
    @Index(name = "idx_bbq_created", columnList = "createdAt")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BlockchainBatchQueue {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(length = 12) private String trackId;
    @Column(nullable = false, length = 64) private String hash;
    @Column(nullable = false, length = 32) private String anchorType;
    @Column(columnDefinition = "TEXT") private String metadata;
    @Column(nullable = false, length = 16) @Builder.Default private String status = "PENDING";
    @Builder.Default private int retryCount = 0;
    @Column(nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
    private Instant submittedAt;
    @Column(length = 128) private String solanaSignature;
    @Column(columnDefinition = "TEXT") private String errorMessage;
}
```

### 6.5 New Repository

```java
package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.BlockchainBatchQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface BlockchainBatchQueueRepository extends JpaRepository<BlockchainBatchQueue, UUID> {
    List<BlockchainBatchQueue> findByStatus(String status);
    long countByStatus(String status);
}
```

---

## 7. Configuration

### 7.1 Application YAML

Add to `shared/shared-platform/src/main/resources/application.yml` (inherited by all services):

```yaml
# ── Solana Blockchain Anchoring ──────────────────────────────────────
solana:
  enabled: ${SOLANA_ENABLED:false}
  network: ${SOLANA_NETWORK:devnet}       # devnet | testnet | mainnet-beta
  rpc-url: ${SOLANA_RPC_URL:https://api.devnet.solana.com}
  # For production, use dedicated RPC:
  # rpc-url: ${SOLANA_RPC_URL:https://rpc.helius.xyz/?api-key=YOUR_KEY}
  wallet-path: ${SOLANA_WALLET_PATH:/etc/tranzfer/solana-wallet.json}
  program-id: ${SOLANA_PROGRAM_ID:}       # Set after program deployment
  batch-interval-seconds: ${SOLANA_BATCH_INTERVAL:60}
  max-batch-size: ${SOLANA_MAX_BATCH_SIZE:100}
  retry-attempts: ${SOLANA_RETRY_ATTEMPTS:3}
  retry-interval-seconds: ${SOLANA_RETRY_INTERVAL:300}

  # Per-type enable/disable toggles — granular control over what gets anchored
  anchor-types:
    transfer-proof: ${SOLANA_ANCHOR_TRANSFER:true}
    audit-integrity: ${SOLANA_ANCHOR_AUDIT:true}
    screening-attestation: ${SOLANA_ANCHOR_SCREENING:true}
    config-versioning: ${SOLANA_ANCHOR_CONFIG:false}
    encryption-proof: ${SOLANA_ANCHOR_ENCRYPTION:false}
    license-tracking: ${SOLANA_ANCHOR_LICENSE:false}
    classification-proof: ${SOLANA_ANCHOR_CLASSIFICATION:false}
    security-event: ${SOLANA_ANCHOR_SECURITY:false}
    storage-proof: ${SOLANA_ANCHOR_STORAGE:false}
    delivery-proof: ${SOLANA_ANCHOR_DELIVERY:false}
    identity-proof: ${SOLANA_ANCHOR_IDENTITY:false}
    protocol-proof: ${SOLANA_ANCHOR_PROTOCOL:false}
    conversion-proof: ${SOLANA_ANCHOR_CONVERSION:false}

  # Explorer URL template for verification links
  explorer-url: ${SOLANA_EXPLORER_URL:https://explorer.solana.com/tx/{signature}?cluster={network}}

  # Low balance alert threshold (SOL)
  low-balance-threshold: ${SOLANA_LOW_BALANCE_THRESHOLD:0.5}
```

### 7.2 Docker Compose Environment Variables

```yaml
# In docker-compose.yml — environment section for each service
environment:
  SOLANA_ENABLED: "false"                    # Enable when ready
  SOLANA_NETWORK: "devnet"
  SOLANA_RPC_URL: "https://api.devnet.solana.com"
  SOLANA_WALLET_PATH: "/etc/tranzfer/solana-wallet.json"
  SOLANA_PROGRAM_ID: ""
  SOLANA_BATCH_INTERVAL: "60"
  SOLANA_MAX_BATCH_SIZE: "100"
  SOLANA_ANCHOR_TRANSFER: "true"
  SOLANA_ANCHOR_AUDIT: "true"
  SOLANA_ANCHOR_SCREENING: "true"

# Volume mount for wallet file
volumes:
  - ./config/solana-wallet.json:/etc/tranzfer/solana-wallet.json:ro
```

### 7.3 Kubernetes ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: solana-config
  namespace: tranzfer
data:
  SOLANA_ENABLED: "true"
  SOLANA_NETWORK: "mainnet-beta"
  SOLANA_RPC_URL: "https://rpc.helius.xyz/?api-key=${HELIUS_API_KEY}"
  SOLANA_BATCH_INTERVAL: "60"
  SOLANA_MAX_BATCH_SIZE: "100"
---
apiVersion: v1
kind: Secret
metadata:
  name: solana-wallet
  namespace: tranzfer
type: Opaque
data:
  solana-wallet.json: <base64-encoded-wallet-keypair>
```

### 7.4 Configuration Properties Class

```java
package com.filetransfer.shared.blockchain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConfigurationProperties(prefix = "solana")
@Getter @Setter
public class SolanaProperties {
    private boolean enabled = false;
    private String network = "devnet";
    private String rpcUrl = "https://api.devnet.solana.com";
    private String walletPath = "/etc/tranzfer/solana-wallet.json";
    private String programId = "";
    private int batchIntervalSeconds = 60;
    private int maxBatchSize = 100;
    private int retryAttempts = 3;
    private int retryIntervalSeconds = 300;
    private Map<String, Boolean> anchorTypes = Map.of(
        "transfer-proof", true,
        "audit-integrity", true,
        "screening-attestation", true,
        "config-versioning", false,
        "encryption-proof", false,
        "license-tracking", false,
        "classification-proof", false,
        "security-event", false,
        "storage-proof", false,
        "delivery-proof", false,
        "identity-proof", false,
        "protocol-proof", false,
        "conversion-proof", false
    );
    private String explorerUrl = "https://explorer.solana.com/tx/{signature}?cluster={network}";
    private double lowBalanceThreshold = 0.5;
}
```

---

## 8. UI Changes

### 8.1 Updated Blockchain.jsx

The existing `Blockchain.jsx` page needs significant enhancements:

```jsx
// ui-service/src/pages/Blockchain.jsx — updated for Solana integration

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import {
  ShieldCheckIcon, MagnifyingGlassIcon, ExclamationTriangleIcon,
  ArrowTopRightOnSquareIcon, FunnelIcon, CurrencyDollarIcon,
  WalletIcon, GlobeAltIcon, CheckCircleIcon, ClockIcon
} from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const ANCHOR_TYPES = [
  'ALL', 'TRANSFER_PROOF', 'AUDIT_INTEGRITY', 'SCREENING_ATTESTATION',
  'CONFIG_VERSION', 'ENCRYPTION_PROOF', 'LICENSE_RECORD',
  'CLASSIFICATION_PROOF', 'SECURITY_EVENT', 'STORAGE_PROOF'
]

const STATUS_COLORS = {
  PENDING: 'text-yellow-500',
  CONFIRMED: 'text-blue-500',
  FINALIZED: 'text-green-500',
  FAILED: 'text-red-500'
}

const STATUS_ICONS = {
  PENDING: ClockIcon,
  CONFIRMED: CheckCircleIcon,
  FINALIZED: ShieldCheckIcon,
  FAILED: ExclamationTriangleIcon
}

export default function Blockchain() {
  const [verifyId, setVerifyId] = useState('')
  const [proof, setProof] = useState(null)
  const [typeFilter, setTypeFilter] = useState('ALL')
  const [networkTab, setNetworkTab] = useState('current')  // current | devnet | testnet | mainnet

  // Fetch anchors
  const { data: anchors = [], isLoading, isError, refetch } = useQuery({
    queryKey: ['bc-anchors', typeFilter],
    queryFn: () => {
      const params = typeFilter !== 'ALL' ? `?type=${typeFilter}` : ''
      return onboardingApi.get(`/api/v1/blockchain/anchors${params}`).then(r => r.data)
    },
    retry: 1
  })

  // Fetch Solana wallet/network status
  const { data: solanaStatus } = useQuery({
    queryKey: ['solana-status'],
    queryFn: () => onboardingApi.get('/api/v1/blockchain/solana/status').then(r => r.data),
    retry: 1, refetchInterval: 30000  // refresh every 30s
  })

  // Fetch cost tracking
  const { data: costData } = useQuery({
    queryKey: ['solana-costs'],
    queryFn: () => onboardingApi.get('/api/v1/blockchain/solana/costs').then(r => r.data),
    retry: 1
  })

  const verify = async () => {
    if (!verifyId) return
    try {
      const r = await onboardingApi.get(`/api/v1/blockchain/verify/${verifyId}`)
      setProof(r.data)
      toast.success(r.data.verified ? 'Verified!' : 'Not verified')
    } catch { toast.error('Verification failed') }
  }

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      {isError && (
        <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ExclamationTriangleIcon className="w-5 h-5 text-red-400" />
            <span className="text-sm text-red-400">Failed to load data</span>
          </div>
          <button onClick={() => refetch()} className="text-xs text-red-400 hover:text-red-300 underline">Retry</button>
        </div>
      )}

      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-primary">Blockchain Notarization</h1>
        <p className="text-secondary text-sm">
          Immutable cryptographic proof of every file transfer — Solana blockchain
        </p>
      </div>

      {/* Solana Status Bar */}
      {solanaStatus && (
        <div className="grid grid-cols-4 gap-4">
          <div className="card flex items-center gap-3">
            <GlobeAltIcon className="w-8 h-8 text-purple-500" />
            <div>
              <p className="text-xs text-secondary">Network</p>
              <p className="font-bold text-sm">{solanaStatus.network}</p>
            </div>
          </div>
          <div className="card flex items-center gap-3">
            <WalletIcon className="w-8 h-8 text-green-500" />
            <div>
              <p className="text-xs text-secondary">Wallet Balance</p>
              <p className="font-bold text-sm">{solanaStatus.balance?.toFixed(4)} SOL</p>
            </div>
          </div>
          <div className="card flex items-center gap-3">
            <ShieldCheckIcon className="w-8 h-8 text-blue-500" />
            <div>
              <p className="text-xs text-secondary">Total Anchors</p>
              <p className="font-bold text-sm">{solanaStatus.totalAnchors?.toLocaleString()}</p>
            </div>
          </div>
          <div className="card flex items-center gap-3">
            <CurrencyDollarIcon className="w-8 h-8 text-yellow-500" />
            <div>
              <p className="text-xs text-secondary">Cost (30d)</p>
              <p className="font-bold text-sm">{costData?.monthly?.toFixed(6)} SOL</p>
            </div>
          </div>
        </div>
      )}

      {/* Verify Input */}
      <div className="card flex gap-3">
        <div className="relative flex-1">
          <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted" />
          <input value={verifyId} onChange={e => setVerifyId(e.target.value.toUpperCase())}
            onKeyDown={e => e.key === 'Enter' && verify()}
            placeholder="Enter Track ID to verify on Solana..."
            className="w-full pl-10 pr-3 py-2 text-sm border rounded-lg font-mono focus:ring-2 focus:ring-blue-500" />
        </div>
        <button onClick={verify}
          className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700">
          Verify
        </button>
      </div>

      {/* Verification Result */}
      {proof && (
        <div className={`card border ${proof.verified ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'}`}>
          <div className="flex items-center gap-2 mb-3">
            <ShieldCheckIcon className={`w-6 h-6 ${proof.verified ? 'text-green-600' : 'text-red-600'}`} />
            <h3 className="font-bold text-lg">{proof.verified ? 'VERIFIED ON SOLANA' : 'NOT VERIFIED'}</h3>
          </div>
          <div className="grid grid-cols-2 gap-3 text-sm">
            <div><span className="text-secondary">Track ID:</span> <strong className="font-mono">{proof.trackId}</strong></div>
            <div><span className="text-secondary">File:</span> <strong>{proof.filename}</strong></div>
            <div><span className="text-secondary">Chain:</span> <strong>{proof.chain}</strong></div>
            <div><span className="text-secondary">Anchor Type:</span> <strong>{proof.anchorType}</strong></div>
            <div><span className="text-secondary">Status:</span>
              <strong className={STATUS_COLORS[proof.anchorStatus]}> {proof.anchorStatus}</strong></div>
            <div><span className="text-secondary">Anchored:</span> <strong>{proof.anchoredAt}</strong></div>
            <div className="col-span-2"><span className="text-secondary">SHA-256:</span>
              <code className="text-xs font-mono break-all">{proof.sha256}</code></div>
            <div className="col-span-2"><span className="text-secondary">Merkle Root:</span>
              <code className="text-xs font-mono break-all">{proof.merkleRoot}</code></div>
            {proof.solanaSignature && (
              <div className="col-span-2"><span className="text-secondary">Solana Tx:</span>
                <a href={proof.verificationUrl} target="_blank" rel="noopener noreferrer"
                   className="text-xs font-mono text-blue-600 hover:underline inline-flex items-center gap-1">
                  {proof.solanaSignature}
                  <ArrowTopRightOnSquareIcon className="w-3 h-3" />
                </a>
              </div>
            )}
            {proof.solanaSlot && (
              <div><span className="text-secondary">Solana Slot:</span>
                <strong className="font-mono">{proof.solanaSlot}</strong></div>
            )}
          </div>
          <p className="text-xs text-secondary mt-3 italic">{proof.nonRepudiation}</p>
        </div>
      )}

      {/* Anchor Type Filter */}
      <div className="flex items-center gap-3">
        <FunnelIcon className="w-4 h-4 text-secondary" />
        <select value={typeFilter} onChange={e => setTypeFilter(e.target.value)}
          className="text-sm border rounded-lg px-3 py-1.5">
          {ANCHOR_TYPES.map(t => <option key={t} value={t}>{t.replace(/_/g, ' ')}</option>)}
        </select>
        <span className="text-xs text-secondary">{anchors.length} anchors</span>
      </div>

      {/* Anchors Table */}
      <div className="card">
        <h3 className="font-semibold text-primary mb-3">Recent Anchors ({anchors.length})</h3>
        {anchors.length === 0
          ? <p className="text-sm text-secondary">No anchors yet. Transfers are anchored every 60 seconds.</p>
          : (
          <table className="w-full"><thead><tr className="border-b">
            <th className="table-header">Track ID</th>
            <th className="table-header">File</th>
            <th className="table-header">Type</th>
            <th className="table-header">Chain</th>
            <th className="table-header">Status</th>
            <th className="table-header">Anchored</th>
            <th className="table-header">Explorer</th>
          </tr></thead><tbody>
            {anchors.slice(0, 30).map(a => {
              const StatusIcon = STATUS_ICONS[a.anchorStatus] || ClockIcon
              return (
                <tr key={a.id} className="table-row cursor-pointer hover:bg-[rgba(100,140,255,0.1)]"
                    onClick={() => { setVerifyId(a.trackId); }}>
                  <td className="table-cell font-mono text-xs font-bold text-blue-600">{a.trackId}</td>
                  <td className="table-cell text-sm truncate max-w-[200px]">{a.filename}</td>
                  <td className="table-cell"><span className="badge badge-purple text-xs">{a.anchorType}</span></td>
                  <td className="table-cell"><span className="badge badge-blue">{a.chain}</span></td>
                  <td className="table-cell">
                    <StatusIcon className={`w-4 h-4 inline ${STATUS_COLORS[a.anchorStatus]}`} />
                    <span className={`text-xs ml-1 ${STATUS_COLORS[a.anchorStatus]}`}>{a.anchorStatus}</span>
                  </td>
                  <td className="table-cell text-xs text-secondary">
                    {a.anchoredAt ? format(new Date(a.anchoredAt), 'MMM d HH:mm') : ''}
                  </td>
                  <td className="table-cell">
                    {a.verificationUrl && (
                      <a href={a.verificationUrl} target="_blank" rel="noopener noreferrer"
                         className="text-blue-500 hover:text-blue-700"
                         onClick={e => e.stopPropagation()}>
                        <ArrowTopRightOnSquareIcon className="w-4 h-4" />
                      </a>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody></table>
        )}
      </div>
    </div>
  )
}
```

### 8.2 New API Endpoints for UI

Add to `BlockchainController.java`:

```java
/** Solana network/wallet status for UI dashboard */
@GetMapping("/solana/status")
public ResponseEntity<Map<String, Object>> solanaStatus() {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("enabled", solanaAnchorService.isEnabled());
    status.put("network", solanaClient.getNetwork());
    status.put("walletAddress", solanaClient.getWalletAddress());
    status.put("balance", solanaClient.getBalance());
    status.put("currentSlot", solanaClient.getCurrentSlot());
    status.put("totalAnchors", anchorRepo.count());
    status.put("pendingAnchors", anchorRepo.countByAnchorStatus("PENDING"));
    status.put("confirmedAnchors", anchorRepo.countByAnchorStatus("CONFIRMED"));
    status.put("finalizedAnchors", anchorRepo.countByAnchorStatus("FINALIZED"));
    return ResponseEntity.ok(status);
}

/** Cost tracking: SOL spent per day/week/month */
@GetMapping("/solana/costs")
public ResponseEntity<Map<String, Object>> solanaCosts() {
    // Calculate based on transaction count * avg cost per tx
    long dailyAnchors = anchorRepo.countByAnchoredAtAfter(Instant.now().minus(1, ChronoUnit.DAYS));
    long weeklyAnchors = anchorRepo.countByAnchoredAtAfter(Instant.now().minus(7, ChronoUnit.DAYS));
    long monthlyAnchors = anchorRepo.countByAnchoredAtAfter(Instant.now().minus(30, ChronoUnit.DAYS));

    double costPerTx = 0.000005; // ~5000 lamports per transaction
    Map<String, Object> costs = new LinkedHashMap<>();
    costs.put("daily", dailyAnchors * costPerTx);
    costs.put("weekly", weeklyAnchors * costPerTx);
    costs.put("monthly", monthlyAnchors * costPerTx);
    costs.put("dailyAnchors", dailyAnchors);
    costs.put("weeklyAnchors", weeklyAnchors);
    costs.put("monthlyAnchors", monthlyAnchors);
    costs.put("costPerTransaction", costPerTx);
    return ResponseEntity.ok(costs);
}

/** Anchors with optional type filter */
@GetMapping("/anchors")
public List<BlockchainAnchor> recentAnchors(
        @RequestParam(required = false) String type) {
    var stream = anchorRepo.findAll().stream()
        .sorted(Comparator.comparing(BlockchainAnchor::getAnchoredAt).reversed());
    if (type != null && !"ALL".equals(type)) {
        stream = stream.filter(a -> type.equals(a.getAnchorType()));
    }
    return stream.limit(50).collect(Collectors.toList());
}
```

---

## 9. Implementation Phases

### Phase 1: Core Anchor Service + Transfer Proofs (2 weeks)

**Week 1:**
- [ ] Add `solanaj` dependency to `shared/shared-platform/pom.xml`
- [ ] Implement `SolanaProperties.java` configuration class
- [ ] Implement `SolanaClient.java` (RPC wrapper, wallet loading, transaction building)
- [ ] Implement `SolanaBorshSerializer.java` (instruction data serialization)
- [ ] Implement `SolanaVerification.java` (verification result type)
- [ ] Implement `AnchorType.java` enum
- [ ] Implement `AnchorRequest.java` queue item
- [ ] Write unit tests for `SolanaClient` (mocked RPC)
- [ ] Write unit tests for Merkle tree computation

**Week 2:**
- [ ] Implement `SolanaAnchorService.java` (batch queue, scheduled processing)
- [ ] Implement `BlockchainBatchQueue.java` entity
- [ ] Implement `BlockchainBatchQueueRepository.java`
- [ ] Create Flyway migrations `V47` and `V48`
- [ ] Update `BlockchainAnchor.java` entity with new fields
- [ ] Modify `BlockchainController.java` to add SOLANA anchor mode
- [ ] Inject `SolanaAnchorService` into onboarding-api transfer flow
- [ ] Add `solana.*` properties to application.yml
- [ ] Integration test: submit anchor to devnet, verify on-chain
- [ ] Compile all 22 services, run full test suite

**Deliverable:** Transfer proofs anchored to Solana devnet. Existing INTERNAL/DOCUMENT modes
unaffected (backward compatible).

### Phase 2: Audit Log Integrity + Screening Attestation (1 week)

- [ ] Add audit log Merkle root anchoring to onboarding-api scheduled job
- [ ] Inject `SolanaAnchorService` into screening-service
- [ ] Anchor sanctions screening results on transfer completion
- [ ] Anchor DLP violation events
- [ ] Anchor quarantine chain-of-custody events
- [ ] Test: verify screening attestation on devnet
- [ ] Test: verify audit Merkle root on devnet

**Deliverable:** Audit logs and screening results have immutable on-chain proof.

### Phase 3: Cross-Service Integration (2 weeks)

**Week 1 — High-value services:**
- [ ] Config Service: flow config version anchoring
- [ ] Encryption Service: key rotation proof, encryption attestation
- [ ] Storage Manager: content proof, tier transitions, deletion attestation
- [ ] License Service: license issuance, activation tracking

**Week 2 — Remaining services:**
- [ ] AI Engine: classification attestation, anomaly evidence
- [ ] Platform Sentinel: security finding evidence, health score history
- [ ] AS2 Service: MDN receipt anchoring
- [ ] Gateway Service: connection routing proof
- [ ] Notification Service: delivery proof
- [ ] SFTP/FTP services: session proof, upload receipt
- [ ] DMZ Proxy: security event anchoring (via RabbitMQ)
- [ ] EDI Converter: conversion attestation (via RabbitMQ)

**Deliverable:** All 22 services can anchor events to Solana. Per-type toggles control what
gets anchored.

### Phase 4: UI Updates + Explorer Integration (1 week)

- [ ] Update `Blockchain.jsx` with Solana status bar, type filters, Explorer links
- [ ] Add `/api/v1/blockchain/solana/status` endpoint
- [ ] Add `/api/v1/blockchain/solana/costs` endpoint
- [ ] Add anchor status column (PENDING/CONFIRMED/FINALIZED) with live updates
- [ ] Add wallet balance warning when below threshold
- [ ] Add network selector UI (display only, config via env vars)
- [ ] Update partner-portal blockchain verification page
- [ ] End-to-end UI test

**Deliverable:** UI shows full Solana integration with Explorer links and cost tracking.

### Phase 5: Solana Program Deployment + Mainnet (1 week)

- [ ] Write and test Solana program (Rust/Anchor) on devnet
- [ ] Security audit of Solana program (account validation, signer checks)
- [ ] Deploy program to Solana mainnet-beta
- [ ] Create mainnet wallet, fund with SOL
- [ ] Configure production environment variables
- [ ] Switch `SOLANA_NETWORK=mainnet-beta` in production
- [ ] Monitor first 24 hours of mainnet anchoring
- [ ] Document program ID, deployment details
- [ ] Update CONFIGURATION.md and FEATURE-GUIDE.md

**Deliverable:** TranzFer MFT anchoring to Solana mainnet in production.

---

## 10. Cost Estimation

### 10.1 Per-Transaction Costs

| Component | Cost (SOL) | Cost (USD @ $150/SOL) |
|-----------|------------|----------------------|
| Transaction fee (base) | 0.000005 | $0.00075 |
| Account rent (BatchAccount, 93 bytes) | 0.00134 | $0.201 |
| **Total per batch transaction** | **~0.00135** | **~$0.202** |

### 10.2 Monthly Cost by Volume (Batch Mode)

Assumptions: 100 transfers per batch, 1 Solana transaction per batch.

| Daily Transfers | Batches/Day | Monthly Batches | Monthly SOL | Monthly USD |
|----------------|-------------|-----------------|-------------|-------------|
| 1,000          | 10          | 300             | 0.405       | $60.75      |
| 10,000         | 100         | 3,000           | 4.05        | $607.50     |
| 100,000        | 1,000       | 30,000          | 40.5        | $6,075      |

### 10.3 Comparison with Ethereum

| Daily Transfers | Solana Monthly | Ethereum L1 Monthly | Savings Factor |
|----------------|---------------|---------------------|----------------|
| 1,000          | $60.75        | $18,000-$90,000     | 296x-1,481x    |
| 10,000         | $607.50       | $180,000-$900,000   | 296x-1,481x    |
| 100,000        | $6,075        | $1.8M-$9M          | 296x-1,481x    |

Even with Ethereum L2 solutions (Arbitrum/Optimism at ~$0.10-$0.50/tx), Solana is
20-100x cheaper.

### 10.4 Cost Optimization Strategies

1. **Increase batch size.** Default is 100, but batches of 500-1000 reduce rent costs per
   transfer by 5-10x (fewer BatchAccount PDAs created).

2. **Close expired accounts.** After a retention period (e.g., 7 years), close old PDA accounts
   to reclaim rent deposits. Solana refunds rent-exempt balance on account closure.

3. **Use account compression.** For high-volume scenarios, Solana's state compression
   (concurrent Merkle trees) can reduce per-anchor cost by 100-1000x at the expense of
   slightly more complex verification.

4. **Dedicated RPC.** Free public RPC has rate limits. Helius or QuickNode dedicated RPC costs
   $50-200/month but provides reliable throughput for production.

### 10.5 Annual Budget Forecast

For a platform processing 10,000 transfers/day:

| Item | Annual Cost |
|------|------------|
| Solana transaction fees + rent | ~$7,290 |
| Dedicated RPC provider (Helius) | ~$1,200 |
| **Total** | **~$8,490/year** |

This is a rounding error compared to the business value of immutable, publicly verifiable
compliance evidence.

---

## 11. Security Considerations

### 11.1 Wallet Key Management

The Solana wallet keypair is the most sensitive component. It controls transaction signing and
SOL spending.

| Environment | Key Storage | Recommended Approach |
|-------------|-------------|---------------------|
| Development | File on disk | `config/solana-wallet.json` (devnet only, test SOL) |
| Staging | Kubernetes Secret | Encrypted at rest, RBAC-controlled |
| Production | HSM or Cloud KMS | AWS CloudHSM, Azure Key Vault, or HashiCorp Vault |

**Production recommendation:** Store the private key in HashiCorp Vault. The `SolanaClient`
loads the key at startup via Vault Agent sidecar. Key never touches disk.

```yaml
# Vault Agent sidecar template for Kubernetes
template:
  source: /vault/templates/solana-wallet.json.tmpl
  destination: /etc/tranzfer/solana-wallet.json
  contents: |
    {{ with secret "secret/data/tranzfer/solana-wallet" }}
    {{ .Data.data.keypair }}
    {{ end }}
```

### 11.2 Transaction Signing Authority

**Who can sign transactions?**

Only the platform wallet. No individual user or service has signing authority. The
`SolanaAnchorService` in shared-platform is the sole signer. Services call it via method
invocation (same JVM) or via REST (cross-service).

**Multi-sig option (future):** For high-security deployments, implement a 2-of-3 multi-sig
where at least two authorized signers must approve each batch. Solana natively supports
multi-sig accounts.

### 11.3 Program Upgrade Authority

The deployed Solana program has an upgrade authority (a keypair that can modify the program).

**Recommendations:**
- **Devnet/testnet:** Upgrade authority = deployment wallet (convenient for iteration)
- **Mainnet:** Transfer upgrade authority to a multi-sig or time-locked governance account
- **After stabilization:** Optionally make the program immutable (renounce upgrade authority)

```bash
# Transfer upgrade authority to multi-sig
solana program set-upgrade-authority <PROGRAM_ID> --new-upgrade-authority <MULTISIG_ADDRESS>

# Make program immutable (irreversible)
solana program set-upgrade-authority <PROGRAM_ID> --final
```

### 11.4 Network Strategy

| Phase | Network | Purpose |
|-------|---------|---------|
| Development | devnet | Free SOL via airdrop, reset periodically |
| QA/Staging | testnet | Stable, free SOL, mirrors mainnet behavior |
| Production | mainnet-beta | Real blockchain, real SOL, real finality |

**Never mix networks.** Environment variables control which network each deployment targets.
The program must be deployed separately to each network.

### 11.5 Graceful Degradation

Solana downtime or RPC failures must never impact file transfers.

```
If Solana RPC unreachable:
  1. SolanaClient.submitBatchAnchor() returns null
  2. SolanaAnchorService persists to blockchain_batch_queue table
  3. Retry scheduler picks up every 5 minutes
  4. After 3 failed retries: alert via notification-service
  5. File transfers continue unaffected at all times
  6. INTERNAL mode anchors continue being created regardless

If wallet balance < threshold:
  1. SolanaClient detects low balance on health check
  2. Platform Sentinel raises SOLANA_LOW_BALANCE alert
  3. Notification service sends alert to ops team
  4. Anchoring continues until balance reaches 0
  5. At 0 balance: graceful fallback to INTERNAL mode
```

### 11.6 Data Privacy

**What goes on-chain:** Only SHA-256 hashes and small metadata hashes. Never:
- File contents
- Filenames
- Partner names or identities
- IP addresses
- Personally identifiable information

The on-chain data is a 32-byte hash that is meaningless without the off-chain mapping stored
in our database. A third party looking at Solana Explorer sees only opaque hashes.

### 11.7 Threat Model

| Threat | Mitigation |
|--------|-----------|
| Wallet keypair stolen | HSM/Vault storage; rotate wallet if compromised; old anchors remain valid |
| Solana chain reorganization | Extremely rare (< 0.001%); wait for FINALIZED status before reporting |
| Program exploit | Anchor framework + audit; upgrade authority for patching; immutable after stable |
| RPC provider compromise | Use multiple RPC providers with failover; verify responses against expected PDAs |
| DoS on our RPC endpoint | Rate limiting at Solana RPC layer; dedicated RPC provider with SLA |
| Replay attack (duplicate anchor) | PDA seeds include merkle_root, making each anchor unique; Solana rejects duplicate PDA creation |

---

## 12. Verification Flow

### 12.1 Internal Verification (TranzFer API)

```
1. User/API calls GET /api/v1/blockchain/verify/{trackId}
2. BlockchainController looks up BlockchainAnchor by trackId
3. Fetches FileTransferRecord to compare SHA-256 checksums
4. If chain == "SOLANA":
   a. Calls SolanaAnchorService.verify(batchMerkleRoot)
   b. SolanaClient fetches BatchAccount PDA from Solana
   c. Verifies: on-chain merkle root == DB merkle root
   d. Verifies: on-chain timestamp >= anchor timestamp
   e. Returns SolanaVerification with Explorer link
5. Response includes complete proof chain:
   {
     "verified": true,
     "trackId": "ABC123",
     "sha256": "a1b2c3...",
     "merkleRoot": "d4e5f6...",
     "batchMerkleRoot": "g7h8i9...",
     "chain": "SOLANA",
     "anchorStatus": "FINALIZED",
     "solanaSignature": "5Kx7...",
     "solanaSlot": 234567890,
     "verificationUrl": "https://explorer.solana.com/tx/5Kx7...?cluster=mainnet-beta",
     "nonRepudiation": "This proof is independently verifiable on the Solana blockchain."
   }
```

### 12.2 External Verification (Third Party: Auditor, Regulator, Partner)

A third party can verify a TranzFer anchor WITHOUT any access to our systems:

```
Step 1: Get Anchor Proof from TranzFer
  - Partner receives proof document (JSON or PDF) containing:
    - File SHA-256 hash
    - Merkle root (individual transfer)
    - Batch Merkle root (on-chain)
    - Merkle proof path (leaf -> batch root)
    - Solana transaction signature
    - Program ID

Step 2: Look Up Transaction on Solana Explorer
  - Navigate to: https://explorer.solana.com/tx/{signature}?cluster=mainnet-beta
  - Confirm: transaction exists, was successful, has expected timestamp
  - Note: on-chain data includes batch_merkle_root

Step 3: Verify Merkle Root Match
  - Extract batch_merkle_root from on-chain BatchAccount data
  - Compare with batch_merkle_root in the proof document
  - Must match exactly (32 bytes)

Step 4: Verify Merkle Proof Path
  - Using the individual file SHA-256 and the Merkle proof path:
    - Compute: hash(leaf + sibling) -> hash(parent + sibling) -> ... -> root
    - Verify: computed root == batch_merkle_root from on-chain
  - This proves the specific file was included in the batch

Step 5: Verify Timestamp
  - On-chain timestamp comes from Solana's Clock sysvar
  - It is set by Solana validators (trustworthy, not set by us)
  - Confirms: the anchor existed on the blockchain at time T

Step 6: Complete Non-Repudiation Chain
  - File SHA-256 proves exact content
  - Merkle proof proves inclusion in batch
  - On-chain batch root proves batch existed at time T
  - Solana validator consensus proves timestamp is genuine
  - Conclusion: file with exact content existed at time T — non-repudiable
```

### 12.3 Verification Without TranzFer Cooperation

Even if TranzFer MFT becomes unavailable, the proof remains verifiable:

```
Given: A proof document saved at transfer time containing:
  - file SHA-256
  - Merkle proof path (list of sibling hashes)
  - batch Merkle root
  - Solana transaction signature
  - TranzFer program ID

Verification:
  1. Recompute Merkle root from file SHA-256 + proof path siblings
  2. Fetch Solana transaction using any Solana RPC or Explorer
  3. Read BatchAccount PDA data from the transaction accounts
  4. Compare computed root with on-chain root
  5. Verified — no TranzFer infrastructure needed
```

This is the gold standard for non-repudiation: the proof survives even if the proving
organization ceases to exist.

### 12.4 Proof Document Format

Each anchored transfer should produce a self-contained proof document:

```json
{
  "version": "1.0",
  "platform": "TranzFer MFT",
  "proofType": "TRANSFER_PROOF",
  "file": {
    "sha256": "a1b2c3d4e5f6...",
    "filename": "payment-batch-2026-04-10.csv",
    "size": 1048576,
    "sender": "partner-acme",
    "receiver": "partner-globex"
  },
  "merkle": {
    "leafHash": "a1b2c3d4e5f6...",
    "proofPath": [
      {"position": "right", "hash": "1234abcd..."},
      {"position": "left",  "hash": "5678efgh..."},
      {"position": "right", "hash": "9012ijkl..."}
    ],
    "batchMerkleRoot": "g7h8i9j0..."
  },
  "solana": {
    "network": "mainnet-beta",
    "programId": "TRZFxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "transactionSignature": "5Kx7yZ...",
    "slot": 234567890,
    "blockTime": 1744300800,
    "pdaAddress": "BATCHxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "explorerUrl": "https://explorer.solana.com/tx/5Kx7yZ...?cluster=mainnet-beta"
  },
  "verification": {
    "instructions": "To verify this proof independently: (1) recompute the Merkle root from leafHash + proofPath, (2) look up the transaction on Solana Explorer, (3) confirm the on-chain batch_merkle_root matches the computed root.",
    "timestamp": "2026-04-10T14:30:00Z"
  }
}
```

---

## 13. Testing Strategy

### 13.1 Unit Tests

| Test Class | What It Tests |
|-----------|--------------|
| `SolanaClientTest` | Wallet loading, instruction data serialization, PDA derivation |
| `SolanaAnchorServiceTest` | Batch queue logic, Merkle root computation, retry logic |
| `SolanaBorshSerializerTest` | Borsh encoding matches Rust program expectations |
| `SolanaVerificationTest` | Account data parsing, verification result construction |
| `SolanaPropertiesTest` | Configuration binding, default values |

All unit tests mock the RPC client. No network calls.

### 13.2 Integration Tests (Devnet)

```java
@SpringBootTest
@ActiveProfiles("test-solana")
class SolanaIntegrationTest {

    @Autowired SolanaAnchorService anchorService;
    @Autowired SolanaClient solanaClient;

    @Test
    void shouldAnchorTransferOnDevnet() {
        // Given
        String trackId = "TEST001";
        String sha256 = "a".repeat(64);

        // When
        anchorService.anchorTransfer(trackId, sha256, Map.of("test", "true"));
        anchorService.processBatch();  // Force immediate batch processing

        // Then — verify on-chain
        // (wait for confirmation, then fetch PDA)
        SolanaVerification result = anchorService.verify(/* batch merkle root */);
        assertThat(result.isVerified()).isTrue();
        assertThat(result.isOnChain()).isTrue();
    }

    @Test
    void shouldGracefullyDegradeWhenSolanaDown() {
        // Given — RPC URL points to unreachable endpoint
        // When
        anchorService.anchorTransfer("TEST002", "b".repeat(64), Map.of());
        anchorService.processBatch();

        // Then — items should be in batch queue for retry
        assertThat(batchQueueRepo.countByStatus("PENDING")).isGreaterThan(0);
    }
}
```

### 13.3 Solana Program Tests (Rust)

```rust
// tests/tranzfer_anchor.ts (Anchor framework test)

import * as anchor from "@coral-xyz/anchor";
import { Program } from "@coral-xyz/anchor";
import { TranzferAnchor } from "../target/types/tranzfer_anchor";
import { expect } from "chai";

describe("tranzfer_anchor", () => {
  const provider = anchor.AnchorProvider.env();
  anchor.setProvider(provider);
  const program = anchor.workspace.TranzferAnchor as Program<TranzferAnchor>;

  it("anchors a single transfer proof", async () => {
    const merkleRoot = Buffer.alloc(32);
    crypto.getRandomValues(merkleRoot);
    const metadataHash = Buffer.alloc(32);
    crypto.getRandomValues(metadataHash);

    const [anchorPda] = anchor.web3.PublicKey.findProgramAddressSync(
      [Buffer.from("anchor"), merkleRoot],
      program.programId
    );
    const [statsPda] = anchor.web3.PublicKey.findProgramAddressSync(
      [Buffer.from("stats")],
      program.programId
    );

    await program.methods
      .anchorSingle(Array.from(merkleRoot), Array.from(metadataHash), { transferProof: {} })
      .accounts({
        anchorAccount: anchorPda,
        statsAccount: statsPda,
        signer: provider.wallet.publicKey,
        systemProgram: anchor.web3.SystemProgram.programId,
      })
      .rpc();

    const account = await program.account.anchorAccount.fetch(anchorPda);
    expect(Buffer.from(account.merkleRoot)).to.deep.equal(merkleRoot);
    expect(account.anchorType).to.deep.equal({ transferProof: {} });
  });

  it("anchors a batch", async () => {
    const batchRoot = Buffer.alloc(32);
    crypto.getRandomValues(batchRoot);

    const [batchPda] = anchor.web3.PublicKey.findProgramAddressSync(
      [Buffer.from("batch"), batchRoot],
      program.programId
    );
    const [statsPda] = anchor.web3.PublicKey.findProgramAddressSync(
      [Buffer.from("stats")],
      program.programId
    );

    await program.methods
      .anchorBatch(Array.from(batchRoot), 42, [{ transferProof: {} }, { auditIntegrity: {} }])
      .accounts({
        batchAccount: batchPda,
        statsAccount: statsPda,
        signer: provider.wallet.publicKey,
        systemProgram: anchor.web3.SystemProgram.programId,
      })
      .rpc();

    const account = await program.account.batchAccount.fetch(batchPda);
    expect(account.count).to.equal(42);
  });

  it("rejects duplicate anchor (same merkle root)", async () => {
    const merkleRoot = Buffer.alloc(32, 0xFF);
    const metadataHash = Buffer.alloc(32);

    const [anchorPda] = anchor.web3.PublicKey.findProgramAddressSync(
      [Buffer.from("anchor"), merkleRoot],
      program.programId
    );
    const [statsPda] = anchor.web3.PublicKey.findProgramAddressSync(
      [Buffer.from("stats")],
      program.programId
    );

    // First anchor succeeds
    await program.methods
      .anchorSingle(Array.from(merkleRoot), Array.from(metadataHash), { transferProof: {} })
      .accounts({ anchorAccount: anchorPda, statsAccount: statsPda,
                   signer: provider.wallet.publicKey, systemProgram: anchor.web3.SystemProgram.programId })
      .rpc();

    // Second anchor with same root fails (PDA already exists)
    try {
      await program.methods
        .anchorSingle(Array.from(merkleRoot), Array.from(metadataHash), { transferProof: {} })
        .accounts({ anchorAccount: anchorPda, statsAccount: statsPda,
                     signer: provider.wallet.publicKey, systemProgram: anchor.web3.SystemProgram.programId })
        .rpc();
      expect.fail("Should have thrown");
    } catch (err) {
      expect(err.toString()).to.contain("already in use");
    }
  });
});
```

---

## 14. Migration from Current Implementation

### 14.1 Backward Compatibility

The migration is fully backward-compatible:

1. **INTERNAL mode continues working.** `solana.enabled=false` (default) means zero behavior
   change. All existing anchors remain valid.

2. **DOCUMENT mode continues working.** RFC 3161 timestamping is orthogonal to Solana.
   A transfer can have both a timestamp token AND a Solana anchor.

3. **New SOLANA mode is additive.** Set `blockchain.anchor-mode=SOLANA` to enable. Or keep
   `INTERNAL` and just set `solana.enabled=true` to add Solana as a secondary anchor.

### 14.2 Migration Steps

```
1. Deploy schema migrations (V47, V48) — adds columns, no data loss
2. Deploy updated shared-platform with SolanaAnchorService
3. Deploy updated onboarding-api with new BlockchainController endpoints
4. Set SOLANA_ENABLED=true, SOLANA_NETWORK=devnet — test
5. Run integration tests against devnet
6. Set SOLANA_NETWORK=mainnet-beta — production
7. Existing INTERNAL anchors remain as-is (no backfill needed)
8. New transfers get both INTERNAL + SOLANA anchors
```

### 14.3 Optional: Backfill Historical Anchors

For existing INTERNAL anchors, we can optionally backfill them to Solana:

```java
@Scheduled(cron = "0 0 2 * * *")  // Run at 2 AM daily
public void backfillHistoricalAnchors() {
    List<BlockchainAnchor> unanchored = anchorRepo.findByChainAndSolanaSignatureIsNull("INTERNAL");
    for (BlockchainAnchor anchor : unanchored) {
        solanaAnchorService.anchorGeneric(
            AnchorType.TRANSFER_PROOF,
            anchor.getSha256(),
            Map.of("backfill", "true", "originalTimestamp", anchor.getAnchoredAt().toString())
        );
    }
    log.info("Backfill: enqueued {} historical anchors for Solana", unanchored.size());
}
```

---

## 15. Appendix: Reference Links

### Solana Documentation
- Solana Docs: https://docs.solana.com/
- Solana Program Library: https://spl.solana.com/
- Anchor Framework: https://www.anchor-lang.com/
- Solana Explorer: https://explorer.solana.com/
- Solana CLI Reference: https://docs.solana.com/cli

### Java SDK
- solanaj (GitHub): https://github.com/skynetcap/solanaj
- solanaj (Maven Central): `com.mmorrell:solanaj`

### RPC Providers (Production)
- Helius: https://helius.xyz/ (Solana-focused, $50-200/month)
- QuickNode: https://quicknode.com/ (multi-chain, $50-300/month)
- Alchemy: https://alchemy.com/ (enterprise, custom pricing)

### Cost References
- Solana fee schedule: https://docs.solana.com/transaction_fees
- Rent exemption calculator: https://docs.solana.com/storage_rent_economics
- Current SOL price: https://www.coingecko.com/en/coins/solana

### Security
- Solana Security Best Practices: https://docs.solana.com/developing/security
- Anchor Security Guide: https://www.anchor-lang.com/docs/security
- Solana Program Audit Checklist: https://github.com/AuditorsOrg/solana-audit-checklist

---

## File Inventory (to be created during implementation)

```
shared/shared-platform/src/main/java/com/filetransfer/shared/blockchain/
  SolanaClient.java              — Low-level Solana RPC wrapper
  SolanaAnchorService.java       — High-level anchor service (batch queue)
  SolanaProperties.java          — Configuration properties
  SolanaVerification.java        — Verification result type
  SolanaBorshSerializer.java     — Borsh serialization for instruction data
  AnchorType.java                — Anchor type enum
  AnchorRequest.java             — Batch queue item

shared/shared-platform/src/main/java/com/filetransfer/shared/entity/
  BlockchainBatchQueue.java      — Queue entity (new)
  BlockchainAnchor.java          — Updated with Solana fields

shared/shared-platform/src/main/java/com/filetransfer/shared/repository/
  BlockchainBatchQueueRepository.java  — Queue repository (new)
  BlockchainAnchorRepository.java      — Updated with new query methods

shared/shared-platform/src/main/resources/db/migration/
  V47__solana_blockchain_columns.sql   — Alter blockchain_anchors
  V48__blockchain_batch_queue.sql      — New queue table

onboarding-api/src/main/java/.../controller/
  BlockchainController.java      — Updated with Solana endpoints

ui-service/src/pages/
  Blockchain.jsx                 — Updated with Solana UI

solana-program/                  — New directory (Anchor project)
  programs/tranzfer_anchor/src/
    lib.rs                       — Solana program (Rust)
  tests/
    tranzfer_anchor.ts           — Program tests
  Anchor.toml                    — Anchor configuration
  Cargo.toml                     — Rust dependencies
```

---

*This plan was designed for TranzFer MFT's 22-microservice architecture. Every code snippet
follows existing conventions (shared-platform for cross-cutting, Spring Boot configuration,
JPA entities, React UI). When the trigger is pulled, execute Phase 1 through Phase 5 in order.*
