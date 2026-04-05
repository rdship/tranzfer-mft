import { useState, useRef, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import axios from 'axios'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  ArrowUpTrayIcon, ArrowDownTrayIcon, FolderIcon, DocumentIcon,
  TrashIcon, ArrowRightOnRectangleIcon, HomeIcon, ChevronRightIcon,
  ArrowPathIcon
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
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB'
  return (bytes / 1073741824).toFixed(2) + ' GB'
}

function getFileIcon(name) {
  const ext = name?.split('.').pop()?.toLowerCase()
  if (['jpg', 'jpeg', 'png', 'gif', 'svg', 'webp'].includes(ext)) return { color: 'text-pink-400', bg: 'bg-pink-50' }
  if (['pdf'].includes(ext)) return { color: 'text-red-400', bg: 'bg-red-50' }
  if (['doc', 'docx'].includes(ext)) return { color: 'text-blue-400', bg: 'bg-blue-50' }
  if (['xls', 'xlsx', 'csv'].includes(ext)) return { color: 'text-green-400', bg: 'bg-green-50' }
  if (['zip', 'tar', 'gz', 'rar', '7z'].includes(ext)) return { color: 'text-amber-400', bg: 'bg-amber-50' }
  if (['edi', 'x12', 'edifact'].includes(ext)) return { color: 'text-purple-400', bg: 'bg-purple-50' }
  if (['enc', 'pgp', 'gpg'].includes(ext)) return { color: 'text-indigo-400', bg: 'bg-indigo-50' }
  return { color: 'text-gray-400', bg: 'bg-gray-50' }
}

export default function FileManager() {
  const { user, logout } = useAuth()
  const [path, setPath] = useState('/')
  const [files, setFiles] = useState([])
  const [loading, setLoading] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [uploadProgress, setUploadProgress] = useState(0)
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
    setUploadProgress(0)
    let ok = 0, fail = 0
    const total = fileList.length
    for (const file of Array.from(fileList)) {
      const formData = new FormData()
      formData.append('file', file)
      formData.append('path', path)
      try {
        await api.post('/files/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
        ok++
      } catch { fail++ }
      setUploadProgress(Math.round(((ok + fail) / total) * 100))
    }
    setUploading(false)
    setUploadProgress(0)
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
    if (!confirm(`Delete "${filename}"? This cannot be undone.`)) return
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
  const folderCount = files.filter(f => f.directory).length
  const fileCount = files.filter(f => !f.directory).length

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <header className="bg-white border-b border-gray-100 px-6 py-3 flex items-center justify-between shadow-sm">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-blue-700 rounded-lg flex items-center justify-center shadow-sm">
            <DocumentIcon className="w-4 h-4 text-white" />
          </div>
          <div>
            <span className="font-bold text-gray-900 text-sm">File Portal</span>
            <span className="text-xs text-gray-400 ml-2 hidden sm:inline">by TranzFer MFT</span>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 bg-blue-100 rounded-full flex items-center justify-center text-blue-700 text-xs font-bold">
              {user?.email?.charAt(0)?.toUpperCase() || 'U'}
            </div>
            <span className="text-sm text-gray-600 hidden sm:inline">{user?.email}</span>
          </div>
          <button onClick={logout} className="p-2 text-gray-400 hover:text-red-500 rounded-lg hover:bg-red-50 transition-all" title="Sign out">
            <ArrowRightOnRectangleIcon className="w-4 h-4" />
          </button>
        </div>
      </header>

      <main className="max-w-5xl mx-auto p-6 space-y-4">
        {/* Breadcrumb + Actions */}
        <div className="flex items-center justify-between">
          <nav className="flex items-center gap-1 text-sm">
            <button onClick={() => loadFiles('/')} className="flex items-center gap-1 text-blue-600 hover:text-blue-700 font-medium">
              <HomeIcon className="w-4 h-4" /> Home
            </button>
            {pathParts.map((part, i) => (
              <span key={i} className="flex items-center gap-1">
                <ChevronRightIcon className="w-3 h-3 text-gray-400" />
                <button onClick={() => loadFiles('/' + pathParts.slice(0, i + 1).join('/'))}
                  className={i === pathParts.length - 1 ? 'font-semibold text-gray-900' : 'text-blue-600 hover:text-blue-700'}>
                  {part}
                </button>
              </span>
            ))}
          </nav>
          <div className="flex items-center gap-2">
            <button onClick={() => loadFiles(path)} className="p-2 text-gray-400 hover:text-blue-600 rounded-lg hover:bg-blue-50 transition-all" title="Refresh">
              <ArrowPathIcon className="w-4 h-4" />
            </button>
            <button onClick={() => fileInputRef.current?.click()} disabled={uploading} className="btn-primary text-sm px-3 py-1.5">
              <ArrowUpTrayIcon className="w-4 h-4" />
              {uploading ? `Uploading ${uploadProgress}%` : 'Upload'}
            </button>
            <input ref={fileInputRef} type="file" multiple className="hidden" onChange={e => upload(e.target.files)} />
          </div>
        </div>

        {/* Upload progress bar */}
        {uploading && (
          <div className="bg-blue-50 rounded-lg p-3">
            <div className="flex items-center justify-between mb-1.5">
              <span className="text-xs font-medium text-blue-700">Uploading files...</span>
              <span className="text-xs text-blue-600">{uploadProgress}%</span>
            </div>
            <div className="w-full bg-blue-200 rounded-full h-1.5">
              <div className="bg-blue-600 h-1.5 rounded-full transition-all duration-300" style={{ width: `${uploadProgress}%` }} />
            </div>
          </div>
        )}

        {/* Summary bar */}
        {!loading && files.length > 0 && (
          <div className="flex items-center gap-4 text-xs text-gray-500">
            {folderCount > 0 && <span>{folderCount} folder{folderCount !== 1 ? 's' : ''}</span>}
            {fileCount > 0 && <span>{fileCount} file{fileCount !== 1 ? 's' : ''}</span>}
          </div>
        )}

        {/* Drop zone + File list */}
        <div
          className={`bg-white rounded-xl border-2 transition-all ${dragging ? 'border-blue-400 bg-blue-50/50 shadow-lg shadow-blue-100' : 'border-gray-100 shadow-sm'}`}
          onDragOver={e => { e.preventDefault(); setDragging(true) }}
          onDragLeave={() => setDragging(false)}
          onDrop={e => { e.preventDefault(); setDragging(false); upload(e.dataTransfer.files) }}>

          {dragging && (
            <div className="p-12 text-center">
              <div className="w-16 h-16 bg-blue-100 rounded-2xl flex items-center justify-center mx-auto mb-3">
                <ArrowUpTrayIcon className="w-8 h-8 text-blue-600" />
              </div>
              <p className="text-blue-700 font-semibold">Drop files to upload</p>
              <p className="text-blue-400 text-sm mt-1">Files will be uploaded to the current folder</p>
            </div>
          )}

          {!dragging && (
            <div className="divide-y divide-gray-50">
              {path !== '/' && (
                <button onClick={() => navigateTo('..')} className="file-row w-full text-left">
                  <div className="w-8 h-8 bg-yellow-50 rounded-lg flex items-center justify-center flex-shrink-0">
                    <FolderIcon className="w-4 h-4 text-yellow-500" />
                  </div>
                  <span className="text-sm text-gray-500">..</span>
                </button>
              )}

              {loading ? (
                <div className="py-16 text-center">
                  <div className="w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full animate-spin mx-auto mb-3" />
                  <p className="text-gray-500 text-sm">Loading files...</p>
                </div>
              ) : files.length === 0 ? (
                <div className="py-20 text-center">
                  <div className="w-16 h-16 bg-gray-100 rounded-2xl flex items-center justify-center mx-auto mb-4">
                    <DocumentIcon className="w-8 h-8 text-gray-300" />
                  </div>
                  <p className="text-gray-600 font-medium">This folder is empty</p>
                  <p className="text-gray-400 text-sm mt-1 max-w-xs mx-auto">Drag and drop files here or click Upload to add files to this directory</p>
                  <button onClick={() => fileInputRef.current?.click()} className="mt-4 btn-primary text-sm px-4 py-2">
                    <ArrowUpTrayIcon className="w-4 h-4" /> Upload Files
                  </button>
                </div>
              ) : files.map((file, i) => {
                const icon = file.directory ? { color: 'text-yellow-500', bg: 'bg-yellow-50' } : getFileIcon(file.name)
                return (
                  <div key={i} className="file-row group">
                    <div className={`w-8 h-8 ${icon.bg} rounded-lg flex items-center justify-center flex-shrink-0`}>
                      {file.directory
                        ? <FolderIcon className={`w-4 h-4 ${icon.color}`} />
                        : <DocumentIcon className={`w-4 h-4 ${icon.color}`} />}
                    </div>
                    <button onClick={() => file.directory ? navigateTo(file.name) : null}
                      className={`flex-1 text-left text-sm font-medium ${file.directory ? 'text-gray-800 hover:text-blue-600' : 'text-gray-700'}`}>
                      {file.name}
                    </button>
                    <span className="text-xs text-gray-400 w-20 text-right flex-shrink-0">{formatBytes(file.size)}</span>
                    <span className="text-xs text-gray-400 w-28 text-right hidden lg:block flex-shrink-0">
                      {file.lastModified ? format(new Date(file.lastModified), 'MMM d, HH:mm') : '—'}
                    </span>
                    <div className="flex gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0">
                      {!file.directory && (
                        <button onClick={() => download(file.name)} className="p-2 rounded-lg hover:bg-blue-50 text-blue-500 transition-colors" title="Download">
                          <ArrowDownTrayIcon className="w-4 h-4" />
                        </button>
                      )}
                      <button onClick={() => deleteFile(file.name)} className="p-2 rounded-lg hover:bg-red-50 text-red-400 transition-colors" title="Delete">
                        <TrashIcon className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>

        <p className="text-center text-xs text-gray-400">
          Drag and drop files anywhere to upload &middot; Powered by TranzFer MFT
        </p>
      </main>
    </div>
  )
}
