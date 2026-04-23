# Known Client-Side Quirks

Non-bugs in TranzFer MFT itself, but behaviors operators may observe from
specific client software. Each entry documents the symptom, the root cause
(client-side), and the server-side handling we ship.

---

## macOS OpenSSH — zero-byte password on SFTP auth

**Symptom (pre-R134AG):**

SFTP connections from the bundled macOS OpenSSH client (`/usr/bin/ssh`,
`sshpass`) sporadically logged `[CredentialService] auth DENIED — password
mismatch` with `passwordLen=0` and `passwordSha256Head=e3b0c442` (the SHA-256
of an empty string), even for accounts with a valid stored BCrypt hash of
the correct password.

**Root cause — client-side, not the server:**

The macOS OpenSSH client dispatches a zero-byte password attempt during the
Apache MINA `ssh-userauth "none"` handshake probe under certain
hostkey-distrust / publickey-fallthrough conditions. The server receives
exactly what the client sent: zero bytes. BCrypt correctly rejected the
empty string against the stored hash of the real password.

Verification path: the same credentials connect cleanly from Paramiko,
paramiko-based CI harnesses, the platform SDK, Linux OpenSSH, and every
non-macOS SSH implementation we tested. The R134AB bytes-level decoder
(`[CredentialService] auth DENIED — password mismatch … passwordLen=0
passwordSha256Head=e3b0c442 …`) pinpointed the issue at R134AF runtime
verification — see `docs/run-reports/R134AF-runtime-verification.md` §2.

**Server handling (R134AG, shipped in `SftpPasswordAuthenticator`):**

Zero-length password attempts are short-circuited before the credential
check. They return `false` without emitting an `auth DENIED` audit event
and without incrementing the lockout counter for the username. The client
sees "this auth method failed" and offers the next method (keyboard-
interactive or publickey), exactly as OpenSSH expects.

Log signature when the filter fires:

```
[R134AG][SftpAuth] rejecting zero-byte password probe
    username=<user> ip=<ip> listener=<listener>
    (not counted toward lockout)
```

Presence of this log line is informational, not an alert.

**Recommendation for integrators:**

Use Paramiko, the TranzFer platform SDK, or any non-macOS OpenSSH for
programmatic access. For interactive macOS use, the connection still
succeeds once the real password is typed at the prompt.

---
