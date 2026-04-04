import { useState, useRef, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import axios from 'axios'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  ArrowUpTrayIcon, ArrowDownTrayIcon, FolderIcon, DocumentIcon,
  TrashIcon, ArrowRightOnRectangleIcon, HomeIcon, ChevronRightIcon
} from '@heroicons/react/24/outline'

const api = axios.create({ baseURL: '/api' })
api.interceptors.request.use(config => {
  const token = localStorage.getItem('ftpweb-token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

function formatBytes(bytes) {
  if (!bytes) return '—'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}

export default function FileManager() {
  const { user, logout } = useAuth()
  const [path, setPath] = useState('/')
  const [files, setFiles] = useState([])
  const [loading, setLoading] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [dragging, setDragging] = useState(false)
  const fileInputRef = useRef(null)

  const loadFiles = useCallback(async (dir = path) => {
    setLoading(true)
    try {
      const res = await api.get('/files/list', { params: { path: dir } })
      setFiles(res.data || [])
      setPath(dir)
    } catch (err) {
      if (err.response?.status === 401) { logout(); return }
      toast.error('Failed to list files')
      setFiles([])
    } finally {
      setLoading(false)
    }
  }, [path, logout])

  useState(() => { loadFiles('/') }, [])

  const upload = async (fileList) => {
    if (!fileList?.length) return
    setUploading(true)
    let ok = 0, fail = 0
    for (const file of Array.from(fileList)) {
      const formData = new FormData()
      formData.append('file', file)
      formData.append('path', path)
      try {
        await api.post('/files/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
        ok++
      } catch { fail++ }
    }
    setUploading(false)
    if (ok) toast.success(`Uploaded ${ok} file${ok > 1 ? 's' : ''}`)
    if (fail) toast.error(`${fail} file${fail > 1 ? 's' : ''} failed`)
    loadFiles(path)
  }

  const download = async (filename) => {
    try {
      const res = await api.get('/files/download', { params: { path: `${path}/${filename}` }, responseType: 'blob' })
      const url = URL.createObjectURL(res.data)
      const a = document.createElement('a')
      a.href = url; a.download = filename; a.click()
      URL.revokeObjectURL(url)
    } catch { toast.error('Download failed') }
  }

  const deleteFile = async (filename) => {
    if (!confirm(`Delete ${filename}?`)) return
    try {
      await api.delete('/files', { params: { path: `${path}/${filename}` } })
      toast.success('Deleted')
      loadFiles(path)
    } catch { toast.error('Delete failed') }
  }

  const navigateTo = (dir) => {
    const newPath = dir === '..' ? path.split('/').slice(0, -1).join('/') || '/' : `${path}/${dir}`.replace('//', '/')
    loadFiles(newPath)
  }

  const pathParts = path.split('/').filter(Boolean)

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <header className="bg-white border-b border-gray-200 px-6 py-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 bg-blue-600 rounded-lg flex items-center justify-center">
            <DocumentIcon className="w-4 h-4 text-white" />
          </div>
          <span className="font-bold text-gray-900">File Portal</span>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-sm text-gray-600">{user?.email}</span>
          <button onClick={logout} className="p-2 text-gray-500 hover:text-red-500 rounded-lg hover:bg-gray-100 transition-colors">
            <ArrowRightOnRectangleIcon className="w-4 h-4" />
          </button>
        </div>
      </header>

      <main className="max-w-5xl mx-auto p-6 space-y-4">
        {/* Breadcrumb + Actions */}
        <div className="flex items-center justify-between">
          <nav className="flex items-center gap-1 text-sm">
            <button onClick={() => loadFiles('/')} className="flex items-center gap-1 text-blue-600 hover:text-blue-700">
              <HomeIcon className="w-4 h-4" /> Home
            </button>
            {pathParts.map((part, i) => (
              <span key={i} className="flex items-center gap-1">
                <ChevronRightIcon className="w-3 h-3 text-gray-400" />
                <button onClick={() => loadFiles('/' + pathParts.slice(0, i + 1).join('/'))}
                  className={i === pathParts.length - 1 ? 'font-medium text-gray-900' : 'text-blue-600 hover:text-blue-700'}>
                  {part}
                </button>
              </span>
            ))}
          </nav>
          <div className="flex gap-2">
            <button onClick={() => fileInputRef.current?.click()} disabled={uploading} className="btn-primary text-sm px-3 py-1.5">
              <ArrowUpTrayIcon className="w-4 h-4" />
              {uploading ? 'Uploading...' : 'Upload'}
            </button>
            <input ref={fileInputRef} type="file" multiple className="hidden" onChange={e => upload(e.target.files)} />
          </div>
        </div>

        {/* Drop zone + File list */}
        <div
          className={`bg-white rounded-xl border-2 transition-colors ${dragging ? 'border-blue-400 bg-blue-50' : 'border-gray-200'}`}
          onDragOver={e => { e.preventDefault(); setDragging(true) }}
          onDragLeave={() => setDragging(false)}
          onDrop={e => { e.preventDefault(); setDragging(false); upload(e.dataTransfer.files) }}>

          {dragging && (
            <div className="p-8 text-center text-blue-600 font-medium">
              <ArrowUpTrayIcon className="w-10 h-10 mx-auto mb-2" />
              Drop files to upload
            </div>
          )}

          {!dragging && (
            <div className="divide-y divide-gray-50">
              {path !== '/' && (
                <button onClick={() => navigateTo('..')} className="file-row w-full text-left">
                  <FolderIcon className="w-5 h-5 text-yellow-500" />
                  <span className="text-sm text-gray-700">..</span>
                </button>
              )}

              {loading ? (
                <div className="py-12 text-center text-gray-500 text-sm">Loading...</div>
              ) : files.length === 0 ? (
                <div className="py-16 text-center">
                  <DocumentIcon className="w-10 h-10 text-gray-300 mx-auto mb-3" />
                  <p className="text-gray-500 text-sm">No files here</p>
                  <p className="text-gray-400 text-xs mt-1">Drag & drop or click Upload to add files</p>
                </div>
              ) : files.map((file, i) => (
                <div key={i} className="file-row group">
                  {file.directory
                    ? <FolderIcon className="w-5 h-5 text-yellow-500 flex-shrink-0" />
                    : <DocumentIcon className="w-5 h-5 text-gray-400 flex-shrink-0" />}
                  <button onClick={() => file.directory ? navigateTo(file.name) : null}
                    className="flex-1 text-left text-sm font-medium text-gray-800">
                    {file.name}
                  </button>
                  <span className="text-xs text-gray-400 w-20 text-right">{formatBytes(file.size)}</span>
                  <span className="text-xs text-gray-400 w-28 text-right hidden lg:block">
                    {file.lastModified ? format(new Date(file.lastModified), 'MMM d, HH:mm') : '—'}
                  </span>
                  <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                    {!file.directory && (
                      <button onClick={() => download(file.name)} className="p-1.5 rounded hover:bg-blue-50 text-blue-500 transition-colors" title="Download">
                        <ArrowDownTrayIcon className="w-4 h-4" />
                      </button>
                    )}
                    <button onClick={() => deleteFile(file.name)} className="p-1.5 rounded hover:bg-red-50 text-red-500 transition-colors" title="Delete">
                      <TrashIcon className="w-4 h-4" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        <p className="text-center text-xs text-gray-400">
          Drag and drop files anywhere to upload • Powered by TranzFer MFT
        </p>
      </main>
    </div>
  )
}
