import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getUsers, updateUser } from '../api/accounts'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import { ExclamationTriangleIcon } from '@heroicons/react/24/outline'

export default function Users() {
  const qc = useQueryClient()
  const [pendingRoleChange, setPendingRoleChange] = useState(null)
  const { data: users = [], isLoading } = useQuery({ queryKey: ['users'], queryFn: getUsers })
  const updateMut = useMutation({
    mutationFn: ({ id, data }) => updateUser(id, data),
    onSuccess: () => { qc.invalidateQueries(['users']); toast.success('User updated') },
    onError: err => toast.error(err.response?.data?.error || 'Failed')
  })

  const handleRoleChange = (user, newRole) => {
    if (newRole === user.role) return
    setPendingRoleChange({ user, newRole })
  }

  const confirmRoleChange = () => {
    if (!pendingRoleChange) return
    updateMut.mutate({ id: pendingRoleChange.user.id, data: { role: pendingRoleChange.newRole } })
    setPendingRoleChange(null)
  }

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">System Users</h1>
        <p className="text-gray-500 text-sm">{users.length} registered users</p></div>
      <div className="card">
        <table className="w-full">
          <thead><tr className="border-b border-gray-100">
            <th className="table-header">Email</th>
            <th className="table-header">Role</th>
            <th className="table-header">Status</th>
            <th className="table-header">Registered</th>
            <th className="table-header">Actions</th>
          </tr></thead>
          <tbody>
            {users.length === 0 ? (
              <tr><td colSpan={5} className="text-center py-8 text-gray-500 text-sm">No users registered yet.</td></tr>
            ) : users.map(u => (
              <tr key={u.id} className="table-row">
                <td className="table-cell font-medium">{u.email}</td>
                <td className="table-cell">
                  <select value={u.role} onChange={e => handleRoleChange(u, e.target.value)}
                    className="text-xs px-2 py-1 border rounded-lg w-auto">
                    <option>USER</option><option>ADMIN</option>
                  </select>
                </td>
                <td className="table-cell">
                  <button onClick={() => updateMut.mutate({ id: u.id, data: { enabled: !u.enabled } })}
                    className={`badge cursor-pointer ${u.enabled !== false ? 'badge-green' : 'badge-red'}`}>
                    {u.enabled !== false ? 'Active' : 'Disabled'}
                  </button>
                </td>
                <td className="table-cell text-gray-500 text-xs">{u.createdAt ? format(new Date(u.createdAt), 'MMM d, yyyy') : '--'}</td>
                <td className="table-cell text-xs text-gray-400">{u.id?.substring(0, 8)}</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {pendingRoleChange && (
        <Modal title="Confirm Role Change" onClose={() => setPendingRoleChange(null)}>
          <div className="space-y-4">
            <div className="flex items-start gap-3">
              <div className="p-2 rounded-full bg-amber-50">
                <ExclamationTriangleIcon className="w-6 h-6 text-amber-600" />
              </div>
              <div>
                <p className="font-medium text-gray-900">Change role for {pendingRoleChange.user.email}?</p>
                <p className="text-sm text-gray-500 mt-1">
                  This will change the role from <span className="font-semibold">{pendingRoleChange.user.role}</span> to <span className="font-semibold">{pendingRoleChange.newRole}</span>.
                  {pendingRoleChange.newRole === 'ADMIN' && ' This grants full administrative access to the platform.'}
                  {pendingRoleChange.newRole === 'USER' && ' This removes administrative privileges.'}
                </p>
              </div>
            </div>
            <div className="flex gap-3 justify-end">
              <button className="btn-secondary" onClick={() => setPendingRoleChange(null)}>Cancel</button>
              <button className="btn-primary" onClick={confirmRoleChange} disabled={updateMut.isPending}>
                {updateMut.isPending ? 'Updating...' : 'Confirm Change'}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
