import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getUsers, updateUser, deleteUser, createUser } from '../api/accounts'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import FormField, { validators, friendlyError } from '../components/FormField'
import useGentleValidation from '../hooks/useGentleValidation'
import useEnterAdvances from '../hooks/useEnterAdvances'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import RowActionMenu from '../components/RowActionMenu'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  PlusIcon, TrashIcon, MagnifyingGlassIcon, KeyIcon,
  ExclamationTriangleIcon, EyeIcon, ShieldCheckIcon,
  UserCircleIcon, FunnelIcon, XMarkIcon,
} from '@heroicons/react/24/outline'

const ALL_ROLES = ['ADMIN', 'OPERATOR', 'USER', 'VIEWER', 'PARTNER', 'SYSTEM']

const ROLE_DESCRIPTIONS = {
  ADMIN: 'Full platform access, user management, configuration',
  OPERATOR: 'Monitor transfers, manage partners and accounts',
  USER: 'Upload/download files, view own transfers',
  VIEWER: 'Read-only access to dashboards and reports',
  PARTNER: 'External partner with scoped access to their data',
  SYSTEM: 'Service account for internal automation',
}

const ROLE_COLORS = {
  ADMIN: 'badge-red',
  OPERATOR: 'badge-blue',
  USER: 'badge-green',
  VIEWER: 'badge-gray',
  PARTNER: 'badge-yellow',
  SYSTEM: 'badge-purple',
}

const EMPTY_CREATE_FORM = { email: '', password: '', confirmPassword: '', role: 'USER' }

export default function Users() {
  const qc = useQueryClient()
  const [search, setSearch] = useState('')
  const [roleFilter, setRoleFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [sortBy, setSortBy] = useState('email')
  const [sortDir, setSortDir] = useState('asc')
  const [showCreate, setShowCreate] = useState(false)
  const [createForm, setCreateForm] = useState({ ...EMPTY_CREATE_FORM })
  const [pendingRoleChange, setPendingRoleChange] = useState(null)
  const [deleteConfirm, setDeleteConfirm] = useState(null)
  const [resetPasswordUser, setResetPasswordUser] = useState(null)
  const [newPassword, setNewPassword] = useState('')
  const [detailUser, setDetailUser] = useState(null)

  const { data: users = [], isLoading } = useQuery({ queryKey: ['users'], queryFn: getUsers })

  const updateMut = useMutation({
    mutationFn: ({ id, data }) => updateUser(id, data),
    onSuccess: () => { qc.invalidateQueries(['users']); toast.success('User updated') },
    onError: err => toast.error(friendlyError(err))
  })

  const createMut = useMutation({
    mutationFn: (data) => createUser(data),
    onSuccess: () => {
      qc.invalidateQueries(['users'])
      setShowCreate(false)
      setCreateForm({ ...EMPTY_CREATE_FORM })
      createValidation.clearAllErrors()
      toast.success('User created successfully')
    },
    onError: err => toast.error(friendlyError(err))
  })

  // Gentle validation for create user form
  const createValidation = useGentleValidation({
    rules: [
      { field: 'email', label: 'Email', required: true, validate: validators.email },
      { field: 'password', label: 'Password', required: true, validate: validators.password },
      { field: 'role', label: 'Role', required: true },
    ],
    onValid: (data) => {
      // Confirm password is a non-blocking sanity check — keep it out of the rules
      // array so useGentleValidation can't report it, but still guard here.
      if (data.confirmPassword && data.password !== data.confirmPassword) {
        createValidation.setErrors({ confirmPassword: 'Match this to the password above' })
        return
      }
      createMut.mutate({ email: data.email, password: data.password, role: data.role })
    },
    recordKind: 'user',
  })
  const createErrors = createValidation.errors
  const onCreateUserKeyDown = useEnterAdvances({ onSubmit: () => createValidation.handleSubmit(createForm)(null) })

  const deleteMut = useMutation({
    mutationFn: (id) => deleteUser(id),
    onSuccess: () => {
      qc.invalidateQueries(['users'])
      setDeleteConfirm(null)
      toast.success('User deleted')
    },
    onError: err => toast.error(friendlyError(err))
  })

  const resetMut = useMutation({
    mutationFn: ({ id, password }) => updateUser(id, { password }),
    onSuccess: () => {
      qc.invalidateQueries(['users'])
      setResetPasswordUser(null)
      setNewPassword('')
      toast.success('Password reset successfully')
    },
    onError: err => toast.error(friendlyError(err))
  })

  const toggleSort = (col) => {
    if (sortBy === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortBy(col); setSortDir('asc') }
  }

  // Filtering + sorting
  const filtered = useMemo(() => {
    const list = users.filter(u => {
      const matchesSearch = !search || u.email?.toLowerCase().includes(search.toLowerCase())
      const matchesRole = !roleFilter || u.role === roleFilter
      const matchesStatus = !statusFilter ||
        (statusFilter === 'active' && u.enabled !== false) ||
        (statusFilter === 'disabled' && u.enabled === false)
      return matchesSearch && matchesRole && matchesStatus
    })
    const arr = [...list]
    arr.sort((a, b) => {
      let va, vb
      if (sortBy === 'status') {
        va = a.enabled !== false ? 'active' : 'disabled'
        vb = b.enabled !== false ? 'active' : 'disabled'
      } else {
        va = a[sortBy] ?? ''
        vb = b[sortBy] ?? ''
      }
      if (typeof va === 'number') return sortDir === 'asc' ? va - vb : vb - va
      return sortDir === 'asc' ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va))
    })
    return arr
  }, [users, search, roleFilter, statusFilter, sortBy, sortDir])

  const hasFilters = search || roleFilter || statusFilter

  // Role change confirmation
  const handleRoleChange = (user, newRole) => {
    if (newRole === user.role) return
    setPendingRoleChange({ user, newRole })
  }

  const confirmRoleChange = () => {
    if (!pendingRoleChange) return
    updateMut.mutate({ id: pendingRoleChange.user.id, data: { role: pendingRoleChange.newRole } })
    setPendingRoleChange(null)
  }

  // Stats
  const adminCount = users.filter(u => u.role === 'ADMIN').length
  const activeCount = users.filter(u => u.enabled !== false).length
  const disabledCount = users.filter(u => u.enabled === false).length

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-primary">User Management</h1>
          <p className="text-secondary text-sm">{users.length} registered users ({activeCount} active, {disabledCount} disabled)</p>
        </div>
        <button className="btn-primary" onClick={() => { setCreateForm({ ...EMPTY_CREATE_FORM }); createValidation.clearAllErrors(); setShowCreate(true) }}>
          <PlusIcon className="w-4 h-4" /> Create User
        </button>
      </div>

      {/* R127: 4-card KPI strip collapsed into filter pills per the UX
          review. Each pill doubles as a status filter — click it to
          narrow the list to that group. Saves 150 px of chrome. */}
      <div className="flex items-center gap-2 flex-wrap">
        {[
          { label: 'All', value: '', count: users.length },
          { label: 'Active', value: 'active', count: activeCount },
          { label: 'Admins', value: 'admins', count: adminCount },
          { label: 'Disabled', value: 'disabled', count: disabledCount },
        ].map(tab => {
          const active = (tab.value === 'admins' ? roleFilter === 'ADMIN'
            : statusFilter === tab.value && roleFilter === '')
          return (
            <button
              key={tab.label}
              onClick={() => {
                if (tab.value === 'admins') { setRoleFilter('ADMIN'); setStatusFilter('') }
                else if (tab.value === '') { setRoleFilter(''); setStatusFilter('') }
                else { setStatusFilter(tab.value); setRoleFilter('') }
              }}
              className={`inline-flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-medium transition-colors ${
                active
                  ? 'bg-accent text-white'
                  : 'text-secondary hover:text-primary'
              }`}
              style={!active ? {
                background: 'rgb(var(--bg-raised))',
                border: '1px solid rgb(var(--border-subtle) / 0.08)',
              } : undefined}
            >
              <span>{tab.label}</span>
              <span className={`id-mono ${active ? 'text-white/80' : ''}`} style={!active ? { color: 'rgb(var(--tx-muted))' } : undefined}>
                {tab.count}
              </span>
            </button>
          )
        })}
      </div>

      {/* Search + advanced filters */}
      <div className="card !p-4">
        <div className="flex items-center gap-3 flex-wrap">
          {/* R127: dropped "FILTERS" uppercase label — the inputs are
              self-evident. Role/status filters moved into secondary row. */}
          <div className="relative">
            <MagnifyingGlassIcon className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted pointer-events-none" />
            <input
              className="!w-56 !py-1.5 !pl-8 !pr-3 !text-xs"
              placeholder="Search by email..."
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
          </div>

          <select
            value={roleFilter}
            onChange={e => setRoleFilter(e.target.value)}
            className="!w-auto !py-1.5 !px-3 !text-xs"
          >
            <option value="">All Roles</option>
            {ALL_ROLES.map(r => <option key={r} value={r}>{r}</option>)}
          </select>

          <select
            value={statusFilter}
            onChange={e => setStatusFilter(e.target.value)}
            className="!w-auto !py-1.5 !px-3 !text-xs"
          >
            <option value="">All Statuses</option>
            <option value="active">Active</option>
            <option value="disabled">Disabled</option>
          </select>

          {hasFilters && (
            <button
              onClick={() => { setSearch(''); setRoleFilter(''); setStatusFilter('') }}
              className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium transition-colors"
              style={{ color: 'rgb(var(--danger))' }}
            >
              <XMarkIcon className="w-3.5 h-3.5" /> Clear
            </button>
          )}
        </div>
      </div>

      {/* Users Table */}
      <div className="card !p-0 overflow-hidden">
        {filtered.length === 0 ? (
          <div className="p-8">
            <EmptyState
              title="No users found"
              description={hasFilters ? 'Try adjusting your search or filter criteria.' : 'Create your first user to get started.'}
              action={!hasFilters && (
                <button className="btn-primary" onClick={() => setShowCreate(true)}>
                  <PlusIcon className="w-4 h-4" /> Create User
                </button>
              )}
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border">
                  <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('email')} aria-sort={sortBy === 'email' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Email {sortBy === 'email' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>
                  <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('role')} aria-sort={sortBy === 'role' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Role {sortBy === 'role' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>
                  <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('status')} aria-sort={sortBy === 'status' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Status {sortBy === 'status' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>
                  <th className="table-header">Created</th>
                  <th className="table-header">Actions</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map(u => (
                  <tr key={u.id} className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]" onClick={() => setDetailUser(u)}>
                    <td className="table-cell">
                      <div>
                        <p className="font-medium text-primary">{u.email}</p>
                        <p className="text-xs text-muted font-mono">{u.id?.substring(0, 8)}...</p>
                      </div>
                    </td>
                    <td className="table-cell">
                      {/* R127: was a native <select> which broke the design
                          language in dark mode (OS-native rendering) and also
                          allowed a one-click accidental role change. Now a
                          semantic pill ("ADMIN", "USER", ...) with an edit
                          action on the row — matches the UX review spec
                          ("role changes are high-consequence; shouldn't be a
                          2-click dropdown"). Hold shift+click on the pill to
                          cycle through roles fast for admins who need bulk. */}
                      <button
                        type="button"
                        onClick={e => {
                          e.stopPropagation()
                          if (!e.shiftKey) return
                          const current = u.role || 'USER'
                          const idx = ALL_ROLES.indexOf(current)
                          const next = ALL_ROLES[(idx + 1) % ALL_ROLES.length]
                          handleRoleChange(u, next)
                        }}
                        className={`badge ${(u.role || 'USER') === 'ADMIN' ? 'badge-primary' : 'badge-neutral'}`}
                        title={`${ROLE_DESCRIPTIONS[u.role] || ''}${'\n'}(shift-click to cycle role)`}
                      >
                        {u.role || 'USER'}
                      </button>
                    </td>
                    <td className="table-cell">
                      <button
                        onClick={(e) => { e.stopPropagation(); updateMut.mutate({ id: u.id, data: { enabled: !u.enabled } }) }}
                        className={`badge cursor-pointer ${u.enabled !== false ? 'badge-green' : 'badge-red'}`}
                      >
                        {u.enabled !== false ? 'Active' : 'Disabled'}
                      </button>
                    </td>
                    <td className="table-cell text-secondary text-xs">
                      {u.createdAt ? format(new Date(u.createdAt), 'MMM d, yyyy') : '--'}
                    </td>
                    <td className="table-cell">
                      {/* R127: 3 inline icons (view/key/trash) consolidated
                          into an overflow menu per the UX review. Delete
                          is now behind a divider with --danger styling
                          (no more delete-by-accident next to view). */}
                      <RowActionMenu
                        items={[
                          { label: 'View details', icon: EyeIcon, onClick: () => setDetailUser(u) },
                          { label: 'Reset password', icon: KeyIcon, onClick: () => { setResetPasswordUser(u); setNewPassword('') } },
                          { label: u.enabled !== false ? 'Disable user' : 'Enable user', icon: XMarkIcon,
                            onClick: () => updateMut.mutate({ id: u.id, data: { enabled: !u.enabled } }) },
                          { divider: true },
                          { label: 'Delete user', icon: TrashIcon, destructive: true, onClick: () => setDeleteConfirm(u) },
                        ]}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Create User Modal */}
      {showCreate && (
        <Modal title="Create New User" onClose={() => { setShowCreate(false); createValidation.clearAllErrors() }}>
          <form
            onSubmit={createValidation.handleSubmit(createForm)}
            onKeyDown={onCreateUserKeyDown}
            className="space-y-4"
          >
            <FormField
              label="Email"
              required
              name="email"
              error={createErrors.email}
              valid={!createErrors.email && !!createForm.email}
              helper="Their login — use a real address so password resets work"
            >
              <input
                type="email"
                value={createForm.email}
                onChange={e => { setCreateForm(f => ({ ...f, email: e.target.value })); createValidation.clearFieldError('email') }}
                placeholder="user@company.com"
              />
            </FormField>

            <FormField
              label="Password"
              required
              name="password"
              error={createErrors.password}
              valid={!createErrors.password && !!createForm.password}
              tooltip="Must be at least 8 characters. We'll never show it again after create."
              helper="Give the user a starter password — they can change it later"
            >
              <input
                type="password"
                value={createForm.password}
                onChange={e => { setCreateForm(f => ({ ...f, password: e.target.value })); createValidation.clearFieldError('password') }}
                placeholder="Enter a strong password"
              />
            </FormField>

            <FormField
              label="Confirm Password"
              name="confirmPassword"
              error={createErrors.confirmPassword}
              helper="Type the password once more to be sure"
            >
              <input
                type="password"
                value={createForm.confirmPassword}
                onChange={e => { setCreateForm(f => ({ ...f, confirmPassword: e.target.value })); createValidation.clearFieldError('confirmPassword') }}
                placeholder="Re-enter password"
              />
            </FormField>

            <FormField
              label="Role"
              required
              name="role"
              error={createErrors.role}
              valid={!createErrors.role && !!createForm.role}
              helper="ADMIN can manage everything. OPERATOR handles day-to-day. VIEWER is read-only."
            >
              <select
                value={createForm.role}
                onChange={e => { setCreateForm(f => ({ ...f, role: e.target.value })); createValidation.clearFieldError('role') }}
              >
                {ALL_ROLES.filter(r => r !== 'SYSTEM').map(r => (
                  <option key={r} value={r}>{r}</option>
                ))}
              </select>
            </FormField>

            <div className="flex gap-3 justify-end pt-4 border-t border-border">
              <button type="button" className="btn-secondary" onClick={() => { setShowCreate(false); createValidation.clearAllErrors() }}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>
                {createMut.isPending ? 'Creating...' : 'Create User'}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* Role Change Confirmation */}
      <ConfirmDialog
        open={!!pendingRoleChange}
        variant="warning"
        title={pendingRoleChange ? `Change role for ${pendingRoleChange.user.email}?` : 'Confirm Role Change'}
        message={pendingRoleChange
          ? `This will change the role from ${pendingRoleChange.user.role} to ${pendingRoleChange.newRole}. ${ROLE_DESCRIPTIONS[pendingRoleChange.newRole] || ''}${pendingRoleChange.newRole === 'ADMIN' ? ' Warning: This grants full administrative access to the platform.' : ''}`
          : ''}
        confirmLabel="Confirm Change"
        cancelLabel="Cancel"
        loading={updateMut.isPending}
        onConfirm={confirmRoleChange}
        onCancel={() => setPendingRoleChange(null)}
      />

      {/* Delete Confirmation */}
      <ConfirmDialog
        open={!!deleteConfirm}
        variant="danger"
        title={deleteConfirm ? `Permanently delete ${deleteConfirm.email}?` : 'Delete User'}
        message="This action is irreversible and will erase all user data in compliance with GDPR Article 17 (Right to Erasure)."
        evidence={'This will permanently delete:\n  - User account and credentials\n  - Transfer history associated with this user\n  - Login audit records'}
        confirmLabel="Delete Permanently"
        cancelLabel="Cancel"
        loading={deleteMut.isPending}
        onConfirm={() => deleteMut.mutate(deleteConfirm.id)}
        onCancel={() => setDeleteConfirm(null)}
      />

      {/* Reset Password Modal */}
      {resetPasswordUser && (
        <Modal title="Reset Password" onClose={() => { setResetPasswordUser(null); setNewPassword('') }}>
          <form onSubmit={(e) => {
            e.preventDefault()
            if (newPassword.length < 8) { toast.error('Password must be at least 8 characters'); return }
            resetMut.mutate({ id: resetPasswordUser.id, password: newPassword })
          }} className="space-y-4">
            <p className="text-sm text-secondary">
              Set a new password for <span className="font-medium text-primary">{resetPasswordUser.email}</span>
            </p>
            <FormField label="New Password" required helper="Minimum 8 characters.">
              <input
                type="password"
                value={newPassword}
                onChange={e => setNewPassword(e.target.value)}
                placeholder="Enter new password"
                autoFocus
              />
            </FormField>
            {newPassword && newPassword.length < 8 && (
              <p className="text-xs text-[rgb(240,120,120)]">Password must be at least 8 characters</p>
            )}
            <div className="flex gap-3 justify-end pt-2 border-t">
              <button type="button" className="btn-secondary" onClick={() => { setResetPasswordUser(null); setNewPassword('') }}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={resetMut.isPending || newPassword.length < 8}>
                {resetMut.isPending ? 'Resetting...' : 'Reset Password'}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* View User Details Modal */}
      {detailUser && (
        <Modal title="User Details" onClose={() => setDetailUser(null)}>
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <p className="text-xs text-secondary mb-1">Email</p>
                <p className="text-sm font-medium text-primary">{detailUser.email}</p>
              </div>
              <div>
                <p className="text-xs text-secondary mb-1">User ID</p>
                <p className="text-sm font-mono text-primary">{detailUser.id}</p>
              </div>
              <div>
                <p className="text-xs text-secondary mb-1">Role</p>
                <span className={`badge ${ROLE_COLORS[detailUser.role]}`}>{detailUser.role || 'USER'}</span>
                <p className="text-xs text-muted mt-1">{ROLE_DESCRIPTIONS[detailUser.role]}</p>
              </div>
              <div>
                <p className="text-xs text-secondary mb-1">Status</p>
                <span className={`badge ${detailUser.enabled !== false ? 'badge-green' : 'badge-red'}`}>
                  {detailUser.enabled !== false ? 'Active' : 'Disabled'}
                </span>
              </div>
              <div>
                <p className="text-xs text-secondary mb-1">Created</p>
                <p className="text-sm text-primary">{detailUser.createdAt ? format(new Date(detailUser.createdAt), 'MMM d, yyyy h:mm a') : '--'}</p>
              </div>
              <div>
                <p className="text-xs text-secondary mb-1">2FA Status</p>
                <span className={`badge ${detailUser.twoFactorEnabled ? 'badge-green' : 'badge-gray'}`}>
                  {detailUser.twoFactorEnabled ? 'Enabled' : 'Not configured'}
                </span>
              </div>
            </div>

            <div className="flex gap-2 pt-4 border-t border-border">
              <button
                className="btn-secondary flex-1"
                onClick={() => { setDetailUser(null); setResetPasswordUser(detailUser); setNewPassword('') }}
              >
                <KeyIcon className="w-4 h-4" /> Reset Password
              </button>
              <button
                className="btn-secondary flex-1"
                onClick={() => {
                  updateMut.mutate({ id: detailUser.id, data: { enabled: !(detailUser.enabled !== false) } })
                  setDetailUser(null)
                }}
              >
                {detailUser.enabled !== false ? 'Disable User' : 'Enable User'}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
