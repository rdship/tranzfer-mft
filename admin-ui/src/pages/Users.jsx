import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getUsers, updateUser } from '../api/accounts'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { format } from 'date-fns'

export default function Users() {
  const qc = useQueryClient()
  const { data: users = [], isLoading } = useQuery({ queryKey: ['users'], queryFn: getUsers })
  const updateMut = useMutation({
    mutationFn: ({ id, data }) => updateUser(id, data),
    onSuccess: () => { qc.invalidateQueries(['users']); toast.success('User updated') },
    onError: err => toast.error(err.response?.data?.error || 'Failed')
  })

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
            {users.map(u => (
              <tr key={u.id} className="table-row">
                <td className="table-cell font-medium">{u.email}</td>
                <td className="table-cell">
                  <select value={u.role} onChange={e => updateMut.mutate({ id: u.id, data: { role: e.target.value } })}
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
                <td className="table-cell text-gray-500 text-xs">{u.createdAt ? format(new Date(u.createdAt), 'MMM d, yyyy') : '—'}</td>
                <td className="table-cell text-xs text-gray-400">{u.id?.substring(0, 8)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
