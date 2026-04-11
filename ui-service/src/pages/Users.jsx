import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getUsers, updateUser, deleteUser, createUser } from '../api/accounts'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import FormField, { validateForm, validators, friendlyError } from '../components/FormField'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
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
  const [createErrors, setCreateErrors] = useState({})
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
      setCreateErrors({})
      toast.success('User created successfully')
    },
    onError: err => toast.error(friendlyError(err))
  })

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

  // Create user validation
  const validateCreate = () => {
    const errs = validateForm(createForm, [
      { field: 'email', label: 'Email', required: true, validate: validators.email },
      { field: 'password', label: 'Password', required: true, validate: validators.password },
    ])
    if (createForm.password && createForm.confirmPassword && createForm.password !== createForm.confirmPassword) {
      errs.confirmPassword = 'Passwords do not match'
    }
    if (createForm.password && !createForm.confirmPassword) {
      errs.confirmPassword = 'Please confirm the password'
    }
    setCreateErrors(errs)
    return Object.keys(errs).length === 0
  }

  const handleCreateSubmit = (e) => {
    e.preventDefault()
    if (!validateCreate()) return
    createMut.mutate({ email: createForm.email, password: createForm.password })
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
        <button className="btn-primary" onClick={() => { setCreateForm({ ...EMPTY_CREATE_FORM }); setCreateErrors({}); setShowCreate(true) }}>
          <PlusIcon className="w-4 h-4" /> Create User
        </button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-4 gap-4">
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-accent-soft rounded-xl flex items-center justify-center">
            <UserCircleIcon className="w-5 h-5 text-accent" />
          </div>
          <div>
            <p className="text-2xl font-bold text-primary">{users.length}</p>
            <p className="text-xs text-secondary">Total Users</p>
          </div>
        </div>
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-[rgb(20,60,40)] rounded-xl flex items-center justify-center">
            <ShieldCheckIcon className="w-5 h-5 text-[rgb(120,220,160)]" />
          </div>
          <div>
            <p className="text-2xl font-bold text-primary">{activeCount}</p>
            <p className="text-xs text-secondary">Active</p>
          </div>
        </div>
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-[rgb(60,20,20)] rounded-xl flex items-center justify-center">
            <ExclamationTriangleIcon className="w-5 h-5 text-[rgb(240,120,120)]" />
          </div>
          <div>
            <p className="text-2xl font-bold text-primary">{adminCount}</p>
            <p className="text-xs text-secondary">Admins</p>
          </div>
        </div>
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-hover rounded-xl flex items-center justify-center">
            <XMarkIcon className="w-5 h-5 text-secondary" />
          </div>
          <div>
            <p className="text-2xl font-bold text-primary">{disabledCount}</p>
            <p className="text-xs text-secondary">Disabled</p>
          </div>
        </div>
      </div>

      {/* Search and Filters */}
      <div className="card !p-4">
        <div className="flex items-center gap-3 flex-wrap">
          <div className="flex items-center gap-1.5 text-muted">
            <FunnelIcon className="w-4 h-4" />
            <span className="text-xs font-medium uppercase tracking-wide">Filters</span>
          </div>

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
              className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-[rgb(240,120,120)] hover:text-[rgb(240,120,120)] hover:bg-[rgb(60,20,20)] rounded-lg transition-colors"
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
                      <select
                        value={u.role || 'USER'}
                        onChange={e => handleRoleChange(u, e.target.value)}
                        onClick={e => e.stopPropagation()}
                        className="text-xs px-2 py-1 border rounded-lg w-auto"
                      >
                        {ALL_ROLES.map(r => <option key={r} value={r}>{r}</option>)}
                      </select>
                      <p className="text-xs text-muted mt-0.5 max-w-[200px] truncate">{ROLE_DESCRIPTIONS[u.role]}</p>
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
                      <div className="flex items-center gap-1">
                        <button
                          onClick={(e) => { e.stopPropagation(); setDetailUser(u) }}
                          className="p-1.5 rounded hover:bg-accent-soft text-accent hover:text-accent transition-colors"
                          title="View details"
                          aria-label="View details"
                        >
                          <EyeIcon className="w-4 h-4" />
                        </button>
                        <button
                          onClick={(e) => { e.stopPropagation(); setResetPasswordUser(u); setNewPassword('') }}
                          className="p-1.5 rounded hover:bg-[rgb(60,50,20)] text-[rgb(240,200,100)] hover:text-[rgb(255,220,120)] transition-colors"
                          title="Reset password"
                          aria-label="Reset password"
                        >
                          <KeyIcon className="w-4 h-4" />
                        </button>
                        <button
                          onClick={(e) => { e.stopPropagation(); setDeleteConfirm(u) }}
                          className="p-1.5 rounded hover:bg-[rgb(60,20,20)] text-[rgb(240,120,120)] hover:text-[rgb(255,140,140)] transition-colors"
                          title="Delete user"
                          aria-label="Delete user"
                        >
                          <TrashIcon className="w-4 h-4" />
                        </button>
                      </div>
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
        <Modal title="Create New User" onClose={() => { setShowCreate(false); setCreateErrors({}) }}>
          <form onSubmit={handleCreateSubmit} className="space-y-4">
            <FormField label="Email" required error={createErrors.email} helper="The user will log in with this email address.">
              <input
                type="email"
                value={createForm.email}
                onChange={e => { setCreateForm(f => ({ ...f, email: e.target.value })); setCreateErrors(prev => { const n = {...prev}; delete n.email; return n }) }}
                placeholder="user@company.com"
                className={createErrors.email ? 'border-red-300 focus:ring-red-500' : ''}
              />
            </FormField>

            <FormField label="Password" required error={createErrors.password} helper="Minimum 8 characters. Use a mix of letters, numbers, and symbols.">
              <input
                type="password"
                value={createForm.password}
                onChange={e => { setCreateForm(f => ({ ...f, password: e.target.value })); setCreateErrors(prev => { const n = {...prev}; delete n.password; return n }) }}
                placeholder="Enter a strong password"
                className={createErrors.password ? 'border-red-300 focus:ring-red-500' : ''}
              />
            </FormField>

            <FormField label="Confirm Password" required error={createErrors.confirmPassword}>
              <input
                type="password"
                value={createForm.confirmPassword}
                onChange={e => { setCreateForm(f => ({ ...f, confirmPassword: e.target.value })); setCreateErrors(prev => { const n = {...prev}; delete n.confirmPassword; return n }) }}
                placeholder="Re-enter password"
                className={createErrors.confirmPassword ? 'border-red-300 focus:ring-red-500' : ''}
              />
            </FormField>

            <FormField label="Role" helper={ROLE_DESCRIPTIONS[createForm.role]}>
              <select
                value={createForm.role}
                onChange={e => setCreateForm(f => ({ ...f, role: e.target.value }))}
              >
                {ALL_ROLES.filter(r => r !== 'SYSTEM').map(r => (
                  <option key={r} value={r}>{r}</option>
                ))}
              </select>
            </FormField>

            <div className="flex gap-3 justify-end pt-4 border-t border-border">
              <button type="button" className="btn-secondary" onClick={() => { setShowCreate(false); setCreateErrors({}) }}>Cancel</button>
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
