/**
 * EvidenceReport — print-optimised compliance report rendered when window.print() is called.
 *
 * Rendered into a hidden <div id="evidence-report"> inside Journey.jsx.
 * @media print CSS (index.css) hides everything else and shows only this element.
 *
 * Usage:
 *   <EvidenceReport journey={journey} execDetail={execDetail} />
 *   window.print()
 */

function fmtBytes(b) {
  if (!b) return '—'
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(2)} MB`
  return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function fmtTs(ts) {
  if (!ts) return '—'
  try { return new Date(ts).toUTCString() } catch { return String(ts) }
}

function fmtMs(ms) {
  if (!ms && ms !== 0) return '—'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(2)} s`
}

const STEP_LABELS = {
  COMPRESS_GZIP: 'Compress (GZIP)', DECOMPRESS_GZIP: 'Decompress (GZIP)',
  COMPRESS_ZIP: 'Compress (ZIP)',   DECOMPRESS_ZIP: 'Decompress (ZIP)',
  ENCRYPT_PGP: 'Encrypt (PGP)',    DECRYPT_PGP: 'Decrypt (PGP)',
  ENCRYPT_AES: 'Encrypt (AES)',    DECRYPT_AES: 'Decrypt (AES)',
  SCREEN: 'Sanctions Screen',      MAILBOX: 'Mailbox Delivery',
  FILE_DELIVERY: 'File Delivery',  ROUTE: 'Route',
  CONVERT_EDI: 'Convert EDI',      RENAME: 'Rename File',
  EXECUTE_SCRIPT: 'Execute Script', APPROVE: 'Admin Approval',
}

export default function EvidenceReport({ journey, execDetail }) {
  if (!journey) return null

  const generatedAt = new Date().toUTCString()
  const steps       = execDetail?.stepResults || []
  const stages      = journey.stages || []
  const auditTrail  = journey.auditTrail || []

  // Extract file size from FILE_RECEIVED stage metadata
  const receivedStage = stages.find(s => s.stage === 'FILE_RECEIVED')
  const fileSizeBytes = receivedStage?.metadata?.size

  const integrityOk = journey.integrityStatus === 'VERIFIED'
  const integrityMismatch = journey.integrityStatus === 'MISMATCH'

  return (
    <div id="evidence-report" style={{ display: 'none', fontFamily: 'Arial, Helvetica, sans-serif', fontSize: '10pt', color: '#000', background: '#fff', padding: '20mm', maxWidth: '190mm', margin: '0 auto', lineHeight: 1.4 }}>

      {/* ── Header ── */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', borderBottom: '2px solid #000', paddingBottom: '8px', marginBottom: '12px' }}>
        <div>
          <div style={{ fontSize: '18pt', fontWeight: 'bold', letterSpacing: '-0.5px' }}>TranzFer MFT</div>
          <div style={{ fontSize: '8pt', color: '#555', marginTop: '2px' }}>AI-First Managed File Transfer Platform</div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <div style={{ fontSize: '14pt', fontWeight: 'bold', textTransform: 'uppercase', letterSpacing: '1px' }}>Compliance Evidence Report</div>
          <div style={{ fontSize: '8pt', color: '#555', marginTop: '4px' }}>Generated: {generatedAt}</div>
          <div style={{ fontSize: '8pt', color: '#555' }}>Report ID: {journey.trackId}</div>
        </div>
      </div>

      {/* ── Transfer Summary ── */}
      <Section title="Transfer Summary">
        <Row label="Track ID"     value={<b style={{ fontFamily: 'Courier New, monospace' }}>{journey.trackId}</b>} />
        <Row label="Filename"     value={journey.filename} />
        {fileSizeBytes && <Row label="File Size"   value={fmtBytes(Number(fileSizeBytes))} />}
        <Row label="Status"       value={<b>{journey.overallStatus}</b>} />
        {journey.totalDurationMs && <Row label="Total Duration" value={fmtMs(journey.totalDurationMs)} />}
        {execDetail?.attemptNumber > 1 && (
          <Row label="Attempt #" value={`${execDetail.attemptNumber} (restarted by ${execDetail.restartedBy || '—'})`} />
        )}
        {receivedStage?.timestamp && <Row label="Received At"   value={fmtTs(receivedStage.timestamp)} />}
        {execDetail?.completedAt  && <Row label="Completed At"  value={fmtTs(execDetail.completedAt)} />}
        {execDetail?.flow?.name   && <Row label="Processing Flow" value={execDetail.flow.name} />}
      </Section>

      {/* ── Integrity Verification ── */}
      <Section title="File Integrity Verification">
        <Row label="Integrity Status" value={
          <span style={{ fontWeight: 'bold', color: integrityOk ? '#166534' : integrityMismatch ? '#991b1b' : '#555' }}>
            {journey.integrityStatus || 'PENDING'}
            {integrityOk && ' ✓'}
            {integrityMismatch && ' ✗ CHECKSUM MISMATCH'}
          </span>
        } />
        {journey.sourceChecksum && (
          <Row label="Source SHA-256" value={<span style={{ fontFamily: 'Courier New, monospace', fontSize: '8pt' }}>{journey.sourceChecksum}</span>} />
        )}
        {journey.destinationChecksum && (
          <Row label="Destination SHA-256" value={<span style={{ fontFamily: 'Courier New, monospace', fontSize: '8pt' }}>{journey.destinationChecksum}</span>} />
        )}
        {journey.sourceChecksum && journey.destinationChecksum && (
          <Row label="Checksums Match" value={
            <span style={{ fontWeight: 'bold', color: journey.sourceChecksum === journey.destinationChecksum ? '#166534' : '#991b1b' }}>
              {journey.sourceChecksum === journey.destinationChecksum ? 'YES ✓' : 'NO ✗'}
            </span>
          } />
        )}
      </Section>

      {/* ── Processing Pipeline ── */}
      {steps.length > 0 && (
        <Section title="Processing Pipeline Steps">
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '9pt' }}>
            <thead>
              <tr style={{ background: '#f0f0f0', borderBottom: '1px solid #ccc' }}>
                {['#', 'Step Type', 'Status', 'Duration', 'Notes'].map(h => (
                  <th key={h} style={{ textAlign: 'left', padding: '4px 6px', fontWeight: 'bold' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {steps.map((step, i) => (
                <tr key={i} style={{ borderBottom: '1px solid #eee', background: i % 2 === 0 ? '#fff' : '#fafafa' }}>
                  <td style={{ padding: '4px 6px' }}>{step.stepIndex + 1}</td>
                  <td style={{ padding: '4px 6px', fontWeight: 500 }}>{STEP_LABELS[step.stepType] || step.stepType}</td>
                  <td style={{ padding: '4px 6px', color: step.status === 'FAILED' ? '#991b1b' : step.status?.startsWith('OK') ? '#166534' : '#555', fontWeight: 'bold' }}>
                    {step.status}
                  </td>
                  <td style={{ padding: '4px 6px', fontFamily: 'Courier New, monospace' }}>{fmtMs(step.durationMs)}</td>
                  <td style={{ padding: '4px 6px', color: '#555', fontSize: '8pt' }}>{step.error || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}

      {/* ── Pipeline Stages ── */}
      {stages.length > 0 && (
        <Section title="Transfer Pipeline Stages">
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '9pt' }}>
            <thead>
              <tr style={{ background: '#f0f0f0', borderBottom: '1px solid #ccc' }}>
                {['Stage', 'Status', 'Detail', 'Timestamp (UTC)'].map(h => (
                  <th key={h} style={{ textAlign: 'left', padding: '4px 6px', fontWeight: 'bold' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {stages.filter(s => !s.stage.startsWith('FLOW_STEP')).map((stage, i) => (
                <tr key={i} style={{ borderBottom: '1px solid #eee', background: i % 2 === 0 ? '#fff' : '#fafafa' }}>
                  <td style={{ padding: '4px 6px', fontWeight: 500 }}>{stage.stage?.replace(/_/g, ' ')}</td>
                  <td style={{ padding: '4px 6px', color: ['FAILED', 'BLOCKED', 'HIT'].includes(stage.status) ? '#991b1b' : ['COMPLETED', 'PASSED', 'CLEAR'].includes(stage.status) ? '#166534' : '#555', fontWeight: 'bold' }}>
                    {stage.status}
                  </td>
                  <td style={{ padding: '4px 6px', color: '#555', fontSize: '8pt' }}>{stage.detail || '—'}</td>
                  <td style={{ padding: '4px 6px', fontFamily: 'Courier New, monospace', fontSize: '8pt' }}>{fmtTs(stage.timestamp)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}

      {/* ── Audit Trail ── */}
      {auditTrail.length > 0 && (
        <Section title="Audit Trail">
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '9pt' }}>
            <thead>
              <tr style={{ background: '#f0f0f0', borderBottom: '1px solid #ccc' }}>
                {['Timestamp (UTC)', 'Action', 'Principal', 'Result', 'Detail'].map(h => (
                  <th key={h} style={{ textAlign: 'left', padding: '4px 6px', fontWeight: 'bold' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {auditTrail.map((entry, i) => (
                <tr key={i} style={{ borderBottom: '1px solid #eee', background: i % 2 === 0 ? '#fff' : '#fafafa' }}>
                  <td style={{ padding: '4px 6px', fontFamily: 'Courier New, monospace', fontSize: '8pt' }}>{fmtTs(entry.timestamp)}</td>
                  <td style={{ padding: '4px 6px', fontWeight: 500 }}>{entry.action}</td>
                  <td style={{ padding: '4px 6px' }}>{entry.principal || 'system'}</td>
                  <td style={{ padding: '4px 6px', color: entry.success === false ? '#991b1b' : '#166534', fontWeight: 'bold' }}>
                    {entry.success === false ? 'FAILED' : 'SUCCESS'}
                  </td>
                  <td style={{ padding: '4px 6px', color: '#555', fontSize: '8pt' }}>{entry.detail || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}

      {/* ── Verification Footer ── */}
      <div style={{ marginTop: '20px', borderTop: '1px solid #ccc', paddingTop: '12px', fontSize: '8pt', color: '#555' }}>
        <div style={{ fontWeight: 'bold', marginBottom: '6px' }}>Verification Statement</div>
        <p style={{ margin: 0, lineHeight: 1.6 }}>
          This Compliance Evidence Report was automatically generated by TranzFer MFT Platform and documents
          the complete lifecycle of transfer <b style={{ fontFamily: 'Courier New, monospace' }}>{journey.trackId}</b>.
          The information contained herein is derived from HMAC-integrity-verified audit logs and is provided
          for regulatory, audit, and compliance purposes. Report generated on {generatedAt}.
        </p>
      </div>
    </div>
  )
}

// ── Helper sub-components ──────────────────────────────────────────────────────

function Section({ title, children }) {
  return (
    <div style={{ marginBottom: '14px' }}>
      <div style={{ fontWeight: 'bold', fontSize: '10pt', textTransform: 'uppercase', letterSpacing: '0.5px', borderBottom: '1px solid #888', paddingBottom: '3px', marginBottom: '6px' }}>
        {title}
      </div>
      {children}
    </div>
  )
}

function Row({ label, value }) {
  return (
    <div style={{ display: 'flex', gap: '8px', marginBottom: '3px', fontSize: '9pt' }}>
      <span style={{ minWidth: '160px', color: '#555', flexShrink: 0 }}>{label}:</span>
      <span>{value}</span>
    </div>
  )
}
