/**
 * TranzFer MFT — Test data factories.
 *
 * Each factory returns a valid request body with unique identifiers so tests
 * never collide. All factory methods accept an optional override object.
 */

const uid = () => Math.random().toString(36).slice(2, 10);
const ts = () => Date.now();

const TestData = {
  // ── Partners ──────────────────────────────────────────────
  partner(overrides = {}) {
    const id = uid();
    return {
      companyName: `PW-Test-Partner-${id}`,
      displayName: `Test Partner ${id}`,
      partnerType: 'EXTERNAL',
      industry: 'Technology',
      website: `https://test-${id}.example.com`,
      protocolsEnabled: ['SFTP'],
      slaTier: 'STANDARD',
      maxFileSizeBytes: 104857600,
      maxTransfersPerDay: 100,
      retentionDays: 30,
      notes: `Playwright test partner ${id}`,
      ...overrides,
    };
  },

  partnerWithContacts(overrides = {}) {
    const id = uid();
    return {
      ...TestData.partner(overrides),
      contacts: [
        {
          name: `Contact ${id}`,
          email: `contact-${id}@example.com`,
          phone: '+1-555-0100',
          primary: true,
        },
      ],
    };
  },

  // ── Users ─────────────────────────────────────────────────
  user(overrides = {}) {
    const id = uid();
    return {
      email: `pw-test-${id}@tranzfer.io`,
      password: 'TestPass123!',
      role: 'USER',
      ...overrides,
    };
  },

  adminUser(overrides = {}) {
    return TestData.user({ role: 'ADMIN', ...overrides });
  },

  operatorUser(overrides = {}) {
    return TestData.user({ role: 'OPERATOR', ...overrides });
  },

  viewerUser(overrides = {}) {
    return TestData.user({ role: 'VIEWER', ...overrides });
  },

  // ── Server Instances ──────────────────────────────────────
  serverInstance(overrides = {}) {
    const id = uid();
    return {
      instanceId: `pw-server-${id}`,
      protocol: 'SFTP',
      name: `PW Test SFTP ${id}`,
      description: `Playwright test server ${id}`,
      internalHost: 'sftp-service',
      internalPort: 2222,
      externalHost: 'localhost',
      externalPort: 2222,
      maxConnections: 50,
      ...overrides,
    };
  },

  ftpServerInstance(overrides = {}) {
    return TestData.serverInstance({
      protocol: 'FTP',
      internalHost: 'ftp-service',
      internalPort: 21,
      externalPort: 21,
      ...overrides,
    });
  },

  // ── Transfer Accounts ─────────────────────────────────────
  account(overrides = {}) {
    const id = uid();
    return {
      protocol: 'SFTP',
      username: `pw-acct-${id}`,
      password: 'TestPass123!',
      homeDir: `/data/sftp/pw-acct-${id}`,
      ...overrides,
    };
  },

  accountWithQos(overrides = {}) {
    return TestData.account({
      uploadBytesPerSecond: 52428800,
      downloadBytesPerSecond: 52428800,
      maxConcurrentSessions: 5,
      priority: 1,
      burstAllowancePercent: 20,
      ...overrides,
    });
  },

  // ── File Flows ────────────────────────────────────────────
  flow(overrides = {}) {
    const id = uid();
    return {
      name: `pw-flow-${id}`,
      description: `Playwright test flow ${id}`,
      filenamePattern: `.*pw-test-${id}.*`,
      direction: 'INBOUND',
      priority: 999,
      active: true,
      steps: [],
      ...overrides,
    };
  },

  flowWithSteps(overrides = {}) {
    return TestData.flow({
      steps: [
        { type: 'CHECKSUM_VERIFY', config: {}, order: 1 },
        { type: 'MAILBOX', config: { folder: '/archive' }, order: 2 },
      ],
      ...overrides,
    });
  },

  encryptionFlow(overrides = {}) {
    return TestData.flow({
      steps: [
        { type: 'ENCRYPT_PGP', config: { keyAlias: 'test-key' }, order: 1 },
        { type: 'COMPRESS_GZIP', config: {}, order: 2 },
        { type: 'MAILBOX', config: { folder: '/encrypted' }, order: 3 },
      ],
      ...overrides,
    });
  },

  ediFlow(overrides = {}) {
    return TestData.flow({
      steps: [
        { type: 'CONVERT_EDI', config: { targetFormat: 'JSON' }, order: 1 },
        { type: 'SCREEN', config: {}, order: 2 },
        { type: 'MAILBOX', config: { folder: '/edi-processed' }, order: 3 },
      ],
      ...overrides,
    });
  },

  // ── Folder Mappings ───────────────────────────────────────
  folderMapping(sourceAccountId, destAccountId, overrides = {}) {
    return {
      sourceAccountId,
      sourcePath: '/inbox',
      destinationAccountId: destAccountId,
      destinationPath: '/outbox',
      filenamePattern: '*.dat',
      ...overrides,
    };
  },

  // ── External Destinations ─────────────────────────────────
  externalDestination(overrides = {}) {
    const id = uid();
    return {
      name: `pw-ext-dest-${id}`,
      type: 'SFTP',
      host: 'external-sftp.example.com',
      port: 22,
      username: `ext-user-${id}`,
      remotePath: '/incoming',
      ...overrides,
    };
  },

  // ── Security Profiles ─────────────────────────────────────
  securityProfile(overrides = {}) {
    const id = uid();
    return {
      name: `pw-sec-profile-${id}`,
      description: `Playwright security profile ${id}`,
      minTlsVersion: 'TLSv1.2',
      ...overrides,
    };
  },

  // ── Screening Policies ────────────────────────────────────
  screeningPolicy(overrides = {}) {
    const id = uid();
    return {
      name: `pw-dlp-${id}`,
      patternType: 'CUSTOM',
      pattern: '\\b\\d{3}-\\d{2}-\\d{4}\\b',
      action: 'FLAG',
      description: `Playwright DLP policy ${id}`,
      ...overrides,
    };
  },

  // ── Notification Rules ────────────────────────────────────
  notificationRule(overrides = {}) {
    const id = uid();
    return {
      name: `pw-notify-${id}`,
      eventType: 'FILE_UPLOAD',
      channel: 'EMAIL',
      recipient: `notify-${id}@example.com`,
      ...overrides,
    };
  },

  // ── AS2 Partnerships ──────────────────────────────────────
  as2Partnership(overrides = {}) {
    const id = uid();
    return {
      name: `pw-as2-${id}`,
      partnerAs2Id: `PARTNER-${id}`,
      ourAs2Id: `TRANZFER-${id}`,
      partnerUrl: `https://as2-${id}.example.com/as2`,
      signingAlgorithm: 'SHA256',
      encryptionAlgorithm: 'AES128',
      mdnRequired: true,
      mdnAsync: false,
      compressionEnabled: false,
      protocol: 'AS2',
      ...overrides,
    };
  },

  // ── Scheduler Tasks ───────────────────────────────────────
  schedulerTask(overrides = {}) {
    const id = uid();
    return {
      name: `pw-task-${id}`,
      cronExpression: '0 0 * * *',
      taskType: 'CLEANUP',
      enabled: false,
      ...overrides,
    };
  },

  // ── Quick Flow ────────────────────────────────────────────
  quickFlow(overrides = {}) {
    const id = uid();
    return {
      name: `pw-quick-${id}`,
      filenamePattern: `PW_QUICK_${id}_*`,
      protocol: 'SFTP',
      direction: 'INBOUND',
      actions: ['CHECKSUM_VERIFY'],
      priority: 500,
      onError: 'RETRY',
      retryCount: 3,
      notifyOnFailure: true,
      ...overrides,
    };
  },
};

module.exports = { TestData, uid, ts };
