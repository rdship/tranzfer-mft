import { useState, useEffect, useMemo, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { configApi, onboardingApi } from '../api/client'
import { PlusIcon, TrashIcon, XMarkIcon } from '@heroicons/react/24/outline'
import toast from 'react-hot-toast'

// ─── Constants ───

const PROTOCOLS = ['SFTP', 'FTP', 'FTPS', 'AS2', 'AS4', 'HTTPS']
const EDI_STANDARDS = ['X12', 'EDIFACT']
const EDI_TYPES = [
  { value: '850', label: '850 - Purchase Order' },
  { value: '855', label: '855 - PO Acknowledgment' },
  { value: '856', label: '856 - Ship Notice' },
  { value: '810', label: '810 - Invoice' },
  { value: '997', label: '997 - Functional Ack' },
  { value: 'INVOIC', label: 'INVOIC - Invoice' },
  { value: 'ORDERS', label: 'ORDERS - Purchase Order' },
  { value: 'DESADV', label: 'DESADV - Despatch Advice' },
]
const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
const GROUP_OPS = ['AND', 'OR']

const FIELD_CATALOG = [
  { name: 'filename',        label: 'Filename',         category: 'File',       type: 'string',  ops: ['GLOB', 'REGEX', 'EQ', 'CONTAINS', 'STARTS_WITH', 'ENDS_WITH'] },
  { name: 'extension',       label: 'Extension',        category: 'File',       type: 'string',  ops: ['EQ', 'IN'] },
  { name: 'fileSize',        label: 'File Size',        category: 'File',       type: 'number',  ops: ['GT', 'LT', 'GTE', 'LTE', 'BETWEEN'] },
  { name: 'protocol',        label: 'Protocol',         category: 'Source',     type: 'enum',    ops: ['EQ', 'IN'], values: PROTOCOLS },
  { name: 'direction',       label: 'Direction',        category: 'Source',     type: 'enum',    ops: ['EQ'], values: ['INBOUND', 'OUTBOUND'] },
  { name: 'accountUsername',  label: 'Account',          category: 'Source',     type: 'string',  ops: ['EQ', 'IN', 'REGEX'] },
  { name: 'partnerSlug',     label: 'Partner',          category: 'Source',     type: 'string',  ops: ['EQ', 'IN'] },
  { name: 'sourcePath',      label: 'Source Path',      category: 'Source',     type: 'string',  ops: ['EQ', 'CONTAINS', 'STARTS_WITH', 'REGEX'] },
  { name: 'sourceIp',        label: 'Source IP',        category: 'Network',    type: 'string',  ops: ['EQ', 'IN', 'CIDR'] },
  { name: 'ediStandard',     label: 'EDI Standard',     category: 'EDI',        type: 'enum',    ops: ['EQ', 'IN'], values: EDI_STANDARDS },
  { name: 'ediType',         label: 'EDI Type',         category: 'EDI',        type: 'enum',    ops: ['EQ', 'IN'], values: EDI_TYPES.map(t => t.value) },
  { name: 'dayOfWeek',       label: 'Day of Week',      category: 'Schedule',   type: 'enum',    ops: ['EQ', 'IN'], values: DAYS },
  { name: 'hour',            label: 'Hour (0-23)',      category: 'Schedule',   type: 'number',  ops: ['EQ', 'GTE', 'LTE'] },
  { name: 'metadata',        label: 'Metadata Key',     category: 'Advanced',   type: 'map',     ops: ['KEY_EQ', 'CONTAINS', 'EQ'] },
]

const FIELD_MAP = Object.fromEntries(FIELD_CATALOG.map(f => [f.name, f]))
const CATEGORIES = [...new Set(FIELD_CATALOG.map(f => f.category))]

const OP_LABELS = {
  EQ: '=', IN: 'in', REGEX: 'regex', GLOB: 'glob', CONTAINS: 'contains',
  STARTS_WITH: 'starts with', ENDS_WITH: 'ends with', GT: '>', LT: '<',
  GTE: '>=', LTE: '<=', BETWEEN: 'between', CIDR: 'CIDR', KEY_EQ: 'key =',
}

// ─── Serialization helpers ───

function newCondition() {
  return { field: 'filename', op: 'GLOB', value: '', values: null, key: null }
}

function newGroup(op = 'AND') {
  return { operator: op, conditions: [newCondition()] }
}

/** Build criteria JSON from legacy flat fields (for editing old flows) */
export function buildCriteriaFromLegacy(flow) {
  const conditions = []
  if (flow.filenamePattern) {
    conditions.push({ field: 'filename', op: 'REGEX', value: flow.filenamePattern })
  }
  if (flow.sourcePath && flow.sourcePath !== '/inbox') {
    conditions.push({ field: 'sourcePath', op: 'CONTAINS', value: flow.sourcePath })
  }
  if (flow.sourceAccountId) {
    conditions.push({ field: 'sourceAccountId', op: 'EQ', value: flow.sourceAccountId })
  }
  if (flow.protocol && flow.protocol !== 'ANY') {
    conditions.push({ field: 'protocol', op: 'EQ', value: flow.protocol })
  }
  if (conditions.length === 0) return null
  if (conditions.length === 1) return { operator: 'AND', conditions }
  return { operator: 'AND', conditions }
}

/** Summarize criteria tree to badge-friendly segments */
export function summarizeCriteria(node) {
  if (!node) return [{ label: 'All files', color: 'gray' }]
  if (node.field) {
    const meta = FIELD_MAP[node.field]
    const label = meta?.label || node.field
    const opLabel = OP_LABELS[node.op] || node.op
    const val = node.values?.join(', ') || node.value || ''
    return [{ label: `${label} ${opLabel} ${val}`, color: categoryColor(meta?.category) }]
  }
  if (node.operator === 'NOT' && node.conditions?.length) {
    const inner = summarizeCriteria(node.conditions[0])
    return inner.map(b => ({ ...b, label: `NOT ${b.label}` }))
  }
  if (node.conditions?.length) {
    return node.conditions.flatMap(c => summarizeCriteria(c))
  }
  return []
}

function categoryColor(cat) {
  const map = { File: 'blue', Source: 'emerald', Network: 'amber', EDI: 'purple', Schedule: 'orange', Advanced: 'slate' }
  return map[cat] || 'gray'
}

// ─── Badge component for read-only summary ───

export function MatchSummaryBadges({ criteria }) {
  const badges = useMemo(() => summarizeCriteria(criteria), [criteria])
  if (!badges.length) return null
  return (
    <div className="flex items-center gap-1 flex-wrap">
      {badges.map((b, i) => (
        <span key={i} className={`inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium
          bg-${b.color}-50 text-${b.color}-700 border border-${b.color}-200`}>
          {b.label}
        </span>
      ))}
    </div>
  )
}

// ─── Main builder ───

export default function MatchCriteriaBuilder({ value, onChange, accounts = [], partners = [] }) {
  const [criteria, setCriteria] = useState(() => value || newGroup('AND'))
  const [testPanel, setTestPanel] = useState(false)
  const [testContext, setTestContext] = useState({ filename: '', protocol: '', partnerSlug: '' })
  const [testResult, setTestResult] = useState(null)

  useEffect(() => {
    if (value !== undefined) setCriteria(value || newGroup('AND'))
  }, [value])

  const handleChange = useCallback((updated) => {
    setCriteria(updated)
    onChange?.(updated)
  }, [onChange])

  // ─── Validation ───
  const validate = useCallback(async () => {
    try {
      const res = await configApi.post('/api/flows/validate-criteria', criteria)
      if (res.data.valid) {
        toast.success('Valid: ' + res.data.summary)
      } else {
        toast.error('Errors: ' + res.data.errors.join(', '))
      }
    } catch { toast.error('Validation failed') }
  }, [criteria])

  // ─── Test Match ───
  const runTest = useCallback(async () => {
    try {
      const res = await configApi.post('/api/flows/test-match', { criteria, fileContext: testContext })
      setTestResult(res.data)
    } catch { toast.error('Test failed') }
  }, [criteria, testContext])

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Match Criteria</label>
        <div className="flex gap-2">
          <button type="button" onClick={validate}
            className="text-xs text-blue-600 hover:text-blue-800 font-medium">
            Validate
          </button>
          <button type="button" onClick={() => setTestPanel(!testPanel)}
            className="text-xs text-emerald-600 hover:text-emerald-800 font-medium">
            {testPanel ? 'Hide Test' : 'Test Match'}
          </button>
          <button type="button" onClick={() => handleChange(null)}
            className="text-xs text-gray-400 hover:text-red-500 font-medium">
            Clear All
          </button>
        </div>
      </div>

      {/* Summary badges */}
      <MatchSummaryBadges criteria={criteria} />

      {/* Criteria tree editor */}
      {criteria && (
        <GroupEditor
          group={criteria}
          onChange={handleChange}
          depth={0}
          accounts={accounts}
          partners={partners}
        />
      )}
      {!criteria && (
        <div className="text-center py-4 border-2 border-dashed border-gray-200 rounded-lg">
          <p className="text-sm text-gray-400 mb-2">No criteria — matches all files</p>
          <button type="button" onClick={() => handleChange(newGroup('AND'))}
            className="text-xs text-blue-600 hover:text-blue-800 font-medium">
            + Add Criteria
          </button>
        </div>
      )}

      {/* Quick-add shortcuts */}
      <div className="flex gap-2 flex-wrap">
        {[
          { label: '+ Protocol', field: 'protocol', op: 'IN', values: ['SFTP'] },
          { label: '+ Filename', field: 'filename', op: 'GLOB', value: '*.*' },
          { label: '+ Partner',  field: 'partnerSlug', op: 'EQ', value: '' },
          { label: '+ EDI Type', field: 'ediType', op: 'EQ', value: '850' },
          { label: '+ Schedule', field: 'dayOfWeek', op: 'IN', values: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'] },
        ].map(shortcut => (
          <button key={shortcut.label} type="button"
            onClick={() => {
              const cond = { field: shortcut.field, op: shortcut.op, value: shortcut.value || null, values: shortcut.values || null, key: null }
              if (!criteria) {
                handleChange({ operator: 'AND', conditions: [cond] })
              } else if (criteria.operator) {
                handleChange({ ...criteria, conditions: [...(criteria.conditions || []), cond] })
              }
            }}
            className="px-2 py-1 text-[11px] rounded-md border border-dashed border-gray-300 text-gray-500 hover:border-blue-400 hover:text-blue-600 transition-colors">
            {shortcut.label}
          </button>
        ))}
      </div>

      {/* Test panel */}
      {testPanel && (
        <div className="bg-gray-50 border border-gray-200 rounded-lg p-3 space-y-2">
          <p className="text-xs font-semibold text-gray-500">Simulate a file to test this criteria</p>
          <div className="grid grid-cols-3 gap-2">
            <input value={testContext.filename} placeholder="filename.edi"
              onChange={e => setTestContext(c => ({ ...c, filename: e.target.value }))}
              className="text-xs font-mono" />
            <select value={testContext.protocol}
              onChange={e => setTestContext(c => ({ ...c, protocol: e.target.value }))}
              className="text-xs">
              <option value="">Protocol...</option>
              {PROTOCOLS.map(p => <option key={p}>{p}</option>)}
            </select>
            <input value={testContext.partnerSlug} placeholder="partner-slug"
              onChange={e => setTestContext(c => ({ ...c, partnerSlug: e.target.value }))}
              className="text-xs" />
          </div>
          <div className="flex items-center gap-3">
            <button type="button" onClick={runTest}
              className="px-3 py-1 text-xs font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded-md">
              Run Test
            </button>
            {testResult && (
              <span className={`text-xs font-medium ${testResult.matched ? 'text-emerald-600' : 'text-red-600'}`}>
                {testResult.matched ? 'MATCHED' : 'NO MATCH'}
              </span>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Group editor (AND/OR with nested conditions) ───

function GroupEditor({ group, onChange, onRemove, depth, accounts, partners }) {
  const borderColors = ['border-blue-300', 'border-emerald-300', 'border-amber-300']
  const bgColors = ['bg-blue-50/30', 'bg-emerald-50/30', 'bg-amber-50/30']
  const borderColor = borderColors[depth % 3]
  const bgColor = bgColors[depth % 3]

  const addCondition = () => {
    onChange({ ...group, conditions: [...(group.conditions || []), newCondition()] })
  }

  const addNestedGroup = () => {
    if (depth >= 2) return // max 3 levels
    onChange({ ...group, conditions: [...(group.conditions || []), newGroup('OR')] })
  }

  const updateChild = (idx, updated) => {
    const newConds = [...group.conditions]
    newConds[idx] = updated
    onChange({ ...group, conditions: newConds })
  }

  const removeChild = (idx) => {
    const newConds = group.conditions.filter((_, i) => i !== idx)
    onChange({ ...group, conditions: newConds })
  }

  const toggleOperator = () => {
    const next = group.operator === 'AND' ? 'OR' : 'AND'
    onChange({ ...group, operator: next })
  }

  return (
    <div className={`border-l-2 ${borderColor} ${bgColor} pl-3 py-2 pr-2 rounded-r-lg space-y-2`}>
      <div className="flex items-center gap-2">
        <button type="button" onClick={toggleOperator}
          className={`px-2 py-0.5 rounded text-[11px] font-bold transition-colors ${
            group.operator === 'AND'
              ? 'bg-blue-100 text-blue-700 hover:bg-blue-200'
              : 'bg-amber-100 text-amber-700 hover:bg-amber-200'
          }`}>
          {group.operator}
        </button>
        <span className="text-[10px] text-gray-400">
          {group.operator === 'AND' ? 'All must match' : 'Any can match'}
        </span>
        <div className="flex-1" />
        <button type="button" onClick={addCondition}
          className="text-[11px] text-blue-600 hover:text-blue-800 font-medium flex items-center gap-0.5">
          <PlusIcon className="w-3 h-3" /> Condition
        </button>
        {depth < 2 && (
          <button type="button" onClick={addNestedGroup}
            className="text-[11px] text-emerald-600 hover:text-emerald-800 font-medium flex items-center gap-0.5">
            <PlusIcon className="w-3 h-3" /> Group
          </button>
        )}
        {onRemove && (
          <button type="button" onClick={onRemove}
            className="text-gray-300 hover:text-red-500 transition-colors">
            <XMarkIcon className="w-4 h-4" />
          </button>
        )}
      </div>

      {(group.conditions || []).map((child, idx) => (
        <div key={idx}>
          {child.operator ? (
            <GroupEditor
              group={child}
              onChange={(updated) => updateChild(idx, updated)}
              onRemove={() => removeChild(idx)}
              depth={depth + 1}
              accounts={accounts}
              partners={partners}
            />
          ) : (
            <ConditionRow
              condition={child}
              onChange={(updated) => updateChild(idx, updated)}
              onRemove={() => removeChild(idx)}
              accounts={accounts}
              partners={partners}
            />
          )}
        </div>
      ))}

      {(!group.conditions || group.conditions.length === 0) && (
        <p className="text-xs text-gray-400 italic pl-1">No conditions — click + to add</p>
      )}
    </div>
  )
}

// ─── Condition row ───

function ConditionRow({ condition, onChange, onRemove, accounts, partners }) {
  const fieldMeta = FIELD_MAP[condition.field]
  const availableOps = fieldMeta?.ops || ['EQ']

  const setField = (field) => {
    const meta = FIELD_MAP[field]
    const defaultOp = meta?.ops?.[0] || 'EQ'
    onChange({ ...condition, field, op: defaultOp, value: '', values: null, key: null })
  }

  const setOp = (op) => {
    const isMulti = op === 'IN'
    onChange({
      ...condition, op,
      value: isMulti ? null : (condition.value || ''),
      values: isMulti ? (condition.values || []) : null,
    })
  }

  return (
    <div className="flex items-center gap-2 py-1">
      {/* Field selector (grouped) */}
      <select value={condition.field} onChange={e => setField(e.target.value)}
        className="text-xs w-32 flex-shrink-0">
        {CATEGORIES.map(cat => (
          <optgroup key={cat} label={cat}>
            {FIELD_CATALOG.filter(f => f.category === cat).map(f => (
              <option key={f.name} value={f.name}>{f.label}</option>
            ))}
          </optgroup>
        ))}
      </select>

      {/* Operator */}
      <select value={condition.op} onChange={e => setOp(e.target.value)}
        className="text-xs w-24 flex-shrink-0">
        {availableOps.map(op => (
          <option key={op} value={op}>{OP_LABELS[op] || op}</option>
        ))}
      </select>

      {/* Key input for metadata */}
      {condition.field === 'metadata' && (
        <input value={condition.key || ''} placeholder="key"
          onChange={e => onChange({ ...condition, key: e.target.value })}
          className="text-xs w-20 font-mono flex-shrink-0" />
      )}

      {/* Value input */}
      <FieldValueInput condition={condition} onChange={onChange} accounts={accounts} partners={partners} />

      {/* Remove */}
      <button type="button" onClick={onRemove}
        className="text-gray-300 hover:text-red-500 transition-colors flex-shrink-0">
        <TrashIcon className="w-3.5 h-3.5" />
      </button>
    </div>
  )
}

// ─── Context-sensitive value input ───

function FieldValueInput({ condition, onChange, accounts, partners }) {
  const { field, op, value, values } = condition
  const meta = FIELD_MAP[field]

  // Multi-value (IN operator)
  if (op === 'IN') {
    const currentValues = values || []

    // Enum fields with checkboxes
    if (meta?.values) {
      return (
        <div className="flex flex-wrap gap-1 flex-1">
          {meta.values.map(v => {
            const label = field === 'ediType' ? EDI_TYPES.find(t => t.value === v)?.label || v : v
            return (
              <label key={v} className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] cursor-pointer transition-colors
                ${currentValues.includes(v)
                  ? 'bg-blue-100 text-blue-700 border border-blue-300'
                  : 'bg-gray-50 text-gray-500 border border-gray-200 hover:border-blue-200'
                }`}>
                <input type="checkbox" className="sr-only"
                  checked={currentValues.includes(v)}
                  onChange={e => {
                    const next = e.target.checked
                      ? [...currentValues, v]
                      : currentValues.filter(x => x !== v)
                    onChange({ ...condition, values: next })
                  }} />
                {label}
              </label>
            )
          })}
        </div>
      )
    }

    // Free-text multi-value
    return (
      <input value={currentValues.join(', ')} placeholder="value1, value2, ..."
        onChange={e => onChange({ ...condition, values: e.target.value.split(',').map(s => s.trim()).filter(Boolean) })}
        className="text-xs flex-1 font-mono" />
    )
  }

  // BETWEEN (two values)
  if (op === 'BETWEEN') {
    const parts = (value || '').split(',')
    return (
      <div className="flex items-center gap-1 flex-1">
        <input value={parts[0] || ''} placeholder="min"
          onChange={e => onChange({ ...condition, value: `${e.target.value},${parts[1] || ''}` })}
          className="text-xs w-20 font-mono" type="number" />
        <span className="text-[10px] text-gray-400">to</span>
        <input value={parts[1] || ''} placeholder="max"
          onChange={e => onChange({ ...condition, value: `${parts[0] || ''},${e.target.value}` })}
          className="text-xs w-20 font-mono" type="number" />
      </div>
    )
  }

  // Enum single-select
  if (meta?.values && (op === 'EQ')) {
    return (
      <select value={value || ''} onChange={e => onChange({ ...condition, value: e.target.value })}
        className="text-xs flex-1">
        <option value="">Select...</option>
        {meta.values.map(v => {
          const label = field === 'ediType' ? EDI_TYPES.find(t => t.value === v)?.label || v : v
          return <option key={v} value={v}>{label}</option>
        })}
      </select>
    )
  }

  // Account dropdown
  if (field === 'accountUsername' && op === 'EQ') {
    return (
      <select value={value || ''} onChange={e => onChange({ ...condition, value: e.target.value })}
        className="text-xs flex-1">
        <option value="">Select account...</option>
        {accounts.map(a => (
          <option key={a.id} value={a.username}>{a.username} ({a.protocol})</option>
        ))}
      </select>
    )
  }

  // Partner dropdown
  if (field === 'partnerSlug' && op === 'EQ') {
    return (
      <select value={value || ''} onChange={e => onChange({ ...condition, value: e.target.value })}
        className="text-xs flex-1">
        <option value="">Select partner...</option>
        {partners.map(p => (
          <option key={p.id} value={p.slug}>{p.name} ({p.slug})</option>
        ))}
      </select>
    )
  }

  // File size with unit helper
  if (field === 'fileSize') {
    return (
      <div className="flex items-center gap-1 flex-1">
        <input value={value || ''} placeholder="size"
          onChange={e => onChange({ ...condition, value: e.target.value })}
          className="text-xs w-24 font-mono" type="number" />
        <span className="text-[10px] text-gray-400">bytes</span>
      </div>
    )
  }

  // Default text input
  const placeholders = {
    filename: '*.edi', extension: 'csv', sourcePath: '/inbox',
    sourceIp: '10.0.0.0/8', metadata: 'value',
  }

  return (
    <input value={value || ''} placeholder={placeholders[field] || 'value'}
      onChange={e => onChange({ ...condition, value: e.target.value })}
      className="text-xs flex-1 font-mono" />
  )
}
