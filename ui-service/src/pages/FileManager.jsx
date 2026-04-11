import { useState, useCallback, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { listFiles, uploadFile, downloadFile, deleteFile, createDirectory, renameFile } from '../api/fileManager'
import { getAccounts } from '../api/accounts'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  FolderIcon, DocumentIcon, ArrowUpTrayIcon, FolderPlusIcon,
  ArrowPathIcon, TrashIcon, ArrowDownTrayIcon, PencilSquareIcon,
  ChevronRightIcon, HomeIcon, Squares2X2Icon, ListBulletIcon,
  DocumentTextIcon, PhotoIcon, MusicalNoteIcon, FilmIcon,
  ArchiveBoxIcon, CodeBracketIcon, TableCellsIcon, XMarkIcon,
  CloudArrowUpIcon, ExclamationTriangleIcon,
} from '@heroicons/react/24/outline'

/* ── Helpers ── */
function humanSize(bytes) {
  if (bytes == null || bytes === 0) return '--'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1073741824) return `${(bytes / 1048576).toFixed(1)} MB`
  return `${(bytes / 1073741824).toFixed(2)} GB`
}

function fileIcon(name, isDir) {
  if (isDir) return FolderIcon
  const ext = name?.split('.').pop()?.toLowerCase()
  if (['jpg', 'jpeg', 'png', 'gif', 'svg', 'webp', 'bmp', 'ico'].includes(ext)) return PhotoIcon
  if (['mp3', 'wav', 'ogg', 'flac', 'aac'].includes(ext)) return MusicalNoteIcon
  if (['mp4', 'mkv', 'avi', 'mov', 'webm'].includes(ext)) return FilmIcon
  if (['zip', 'tar', 'gz', 'rar', '7z', 'bz2'].includes(ext)) return ArchiveBoxIcon
  if (['js', 'jsx', 'ts', 'tsx', 'py', 'java', 'rb', 'go', 'rs', 'c', 'cpp', 'h', 'sh', 'yml', 'yaml', 'json', 'xml'].includes(ext)) return CodeBracketIcon
  if (['csv', 'xls', 'xlsx'].includes(ext)) return TableCellsIcon
  if (['txt', 'md', 'log', 'pdf', 'doc', 'docx', 'rtf'].includes(ext)) return DocumentTextIcon
  return DocumentIcon
}

function iconColor(name, isDir) {
  if (isDir) return 'rgb(100,140,255)'
  const ext = name?.split('.').pop()?.toLowerCase()
  if (['jpg', 'jpeg', 'png', 'gif', 'svg', 'webp'].includes(ext)) return 'rgb(72,199,174)'
  if (['zip', 'tar', 'gz', 'rar', '7z'].includes(ext)) return 'rgb(240,180,80)'
  if (['mp4', 'mkv', 'avi', 'mov'].includes(ext)) return 'rgb(190,140,255)'
  return 'rgb(160,165,175)'
}

function parsePath(path) {
  return path.split('/').filter(Boolean)
}

export default function FileManager() {
  const qc = useQueryClient()
  const fileInputRef = useRef(null)
  const [accountId, setAccountId] = useState('')
  const [currentPath, setCurrentPath] = useState('/')
  const [viewMode, setViewMode] = useState('list')
  const [showNewFolder, setShowNewFolder] = useState(false)
  const [newFolderName, setNewFolderName] = useState('')
  const [renamingItem, setRenamingItem] = useState(null)
  const [renameValue, setRenameValue] = useState('')
  const [deleteConfirm, setDeleteConfirm] = useState(null)
  const [dragOver, setDragOver] = useState(false)
  const [uploadProgress, setUploadProgress] = useState(null)

  /* ── Queries ── */
  const { data: accounts = [], isLoading: loadingAccounts } = useQuery({
    queryKey: ['accounts'],
    queryFn: getAccounts,
  })

  const { data: files = [], isLoading: loadingFiles, refetch: refetchFiles } = useQuery({
    queryKey: ['file-manager', accountId, currentPath],
    queryFn: () => listFiles(currentPath, accountId),
    enabled: !!accountId,
  })

  /* ── Mutations ── */
  const uploadMut = useMutation({
    mutationFn: ({ file }) => uploadFile(currentPath, accountId, file, {
      onUploadProgress: (e) => {
        const pct = Math.round((e.loaded / (e.total || 1)) * 100)
        setUploadProgress({ name: file.name, percent: pct })
      },
    }),
    onSuccess: () => {
      qc.invalidateQueries(['file-manager', accountId, currentPath])
      setUploadProgress(null)
      toast.success('File uploaded')
    },
    onError: (err) => {
      setUploadProgress(null)
      toast.error(err?.response?.data?.message || 'Upload failed')
    },
  })

  const mkdirMut = useMutation({
    mutationFn: (folderPath) => createDirectory(folderPath, accountId),
    onSuccess: () => {
      qc.invalidateQueries(['file-manager', accountId, currentPath])
      setShowNewFolder(false)
      setNewFolderName('')
      toast.success('Folder created')
    },
    onError: (err) => toast.error(err?.response?.data?.message || 'Failed to create folder'),
  })

  const deleteMut = useMutation({
    mutationFn: (path) => deleteFile(path, accountId),
    onSuccess: () => {
      qc.invalidateQueries(['file-manager', accountId, currentPath])
      setDeleteConfirm(null)
      toast.success('Deleted successfully')
    },
    onError: (err) => toast.error(err?.response?.data?.message || 'Delete failed'),
  })

  const renameMut = useMutation({
    mutationFn: ({ oldPath, newPath }) => renameFile(oldPath, newPath, accountId),
    onSuccess: () => {
      qc.invalidateQueries(['file-manager', accountId, currentPath])
      setRenamingItem(null)
      setRenameValue('')
      toast.success('Renamed')
    },
    onError: (err) => toast.error(err?.response?.data?.message || 'Rename failed'),
  })

  /* ── Handlers ── */
  const navigateTo = (path) => setCurrentPath(path)

  const navigateUp = () => {
    const segments = parsePath(currentPath)
    segments.pop()
    setCurrentPath('/' + segments.join('/'))
  }

  const handleBreadcrumbClick = (index) => {
    const segments = parsePath(currentPath)
    setCurrentPath('/' + segments.slice(0, index + 1).join('/'))
  }

  const handleFileClick = (item) => {
    if (item.directory) {
      const next = currentPath === '/'
        ? '/' + item.name
        : currentPath + '/' + item.name
      navigateTo(next)
    }
  }

  const handleDownload = async (item) => {
    try {
      const filePath = currentPath === '/'
        ? '/' + item.name
        : currentPath + '/' + item.name
      const blob = await downloadFile(filePath, accountId)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = item.name
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
      toast.success('Download started')
    } catch {
      toast.error('Download failed')
    }
  }

  const handleUpload = useCallback((fileList) => {
    if (!accountId) {
      toast.error('Select an account first')
      return
    }
    Array.from(fileList).forEach((file) => {
      setUploadProgress({ name: file.name, percent: 0 })
      uploadMut.mutate({ file })
    })
  }, [accountId, uploadMut])

  const handleDrop = useCallback((e) => {
    e.preventDefault()
    setDragOver(false)
    if (e.dataTransfer.files?.length) handleUpload(e.dataTransfer.files)
  }, [handleUpload])

  const handleDragOver = (e) => { e.preventDefault(); setDragOver(true) }
  const handleDragLeave = () => setDragOver(false)

  const handleCreateFolder = () => {
    if (!newFolderName.trim()) return
    const folderPath = currentPath === '/'
      ? '/' + newFolderName.trim()
      : currentPath + '/' + newFolderName.trim()
    mkdirMut.mutate(folderPath)
  }

  const handleRenameSubmit = () => {
    if (!renameValue.trim() || !renamingItem) return
    const oldPath = currentPath === '/'
      ? '/' + renamingItem.name
      : currentPath + '/' + renamingItem.name
    const newPath = currentPath === '/'
      ? '/' + renameValue.trim()
      : currentPath + '/' + renameValue.trim()
    renameMut.mutate({ oldPath, newPath })
  }

  const handleDeleteConfirm = () => {
    if (!deleteConfirm) return
    const path = currentPath === '/'
      ? '/' + deleteConfirm.name
      : currentPath + '/' + deleteConfirm.name
    deleteMut.mutate(path)
  }

  const openRename = (item) => {
    setRenamingItem(item)
    setRenameValue(item.name)
  }

  const pathSegments = parsePath(currentPath)

  /* Sort: directories first, then alphabetical */
  const sortedFiles = [...files].sort((a, b) => {
    if (a.directory && !b.directory) return -1
    if (!a.directory && b.directory) return 1
    return (a.name || '').localeCompare(b.name || '')
  })

  return (
    <div className="page-enter space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between flex-wrap gap-4">
        <div>
          <h1 className="page-title">File Manager</h1>
          <p className="text-sm text-secondary mt-1">Browse and manage files on transfer accounts</p>
        </div>
        <div className="flex items-center gap-3">
          <select
            value={accountId}
            onChange={(e) => { setAccountId(e.target.value); setCurrentPath('/') }}
            className="w-64"
          >
            <option value="">Select account...</option>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.username} ({a.protocol})
              </option>
            ))}
          </select>
        </div>
      </div>

      {!accountId && (
        <EmptyState
          title="Select an Account"
          description="Choose a transfer account from the dropdown to browse its files."
        />
      )}

      {accountId && (
        <>
          {/* Breadcrumb + Toolbar */}
          <div className="card">
            <div className="flex items-center justify-between flex-wrap gap-3">
              {/* Breadcrumb */}
              <div className="flex items-center gap-1 text-sm flex-wrap">
                <button
                  onClick={() => navigateTo('/')}
                  className="flex items-center gap-1 px-2 py-1 rounded-md hover:bg-hover transition-colors text-accent"
                >
                  <HomeIcon className="w-4 h-4" />
                  <span>Root</span>
                </button>
                {pathSegments.map((seg, i) => (
                  <div key={i} className="flex items-center gap-1">
                    <ChevronRightIcon className="w-3 h-3 text-muted" />
                    <button
                      onClick={() => handleBreadcrumbClick(i)}
                      className={`px-2 py-1 rounded-md transition-colors ${
                        i === pathSegments.length - 1
                          ? 'text-primary font-medium'
                          : 'text-secondary hover:bg-hover hover:text-primary'
                      }`}
                    >
                      {seg}
                    </button>
                  </div>
                ))}
              </div>

              {/* Toolbar buttons */}
              <div className="flex items-center gap-2">
                <button
                  onClick={() => fileInputRef.current?.click()}
                  className="btn-primary"
                >
                  <ArrowUpTrayIcon className="w-4 h-4" />
                  Upload
                </button>
                <input
                  ref={fileInputRef}
                  type="file"
                  multiple
                  className="hidden"
                  onChange={(e) => { if (e.target.files?.length) handleUpload(e.target.files); e.target.value = '' }}
                />
                <button
                  onClick={() => setShowNewFolder(true)}
                  className="btn-secondary"
                >
                  <FolderPlusIcon className="w-4 h-4" />
                  New Folder
                </button>
                <button
                  onClick={() => refetchFiles()}
                  className="btn-ghost"
                  title="Refresh"
                  aria-label="Refresh"
                >
                  <ArrowPathIcon className="w-4 h-4" />
                </button>
                <div className="flex items-center border border-border rounded-lg overflow-hidden">
                  <button
                    onClick={() => setViewMode('list')}
                    className={`p-1.5 transition-colors ${viewMode === 'list' ? 'bg-accent/15 text-accent' : 'text-secondary hover:text-primary'}`}
                    title="List view"
                    aria-label="List view"
                  >
                    <ListBulletIcon className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => setViewMode('grid')}
                    className={`p-1.5 transition-colors ${viewMode === 'grid' ? 'bg-accent/15 text-accent' : 'text-secondary hover:text-primary'}`}
                    title="Grid view"
                    aria-label="Grid view"
                  >
                    <Squares2X2Icon className="w-4 h-4" />
                  </button>
                </div>
              </div>
            </div>
          </div>

          {/* Upload progress */}
          {uploadProgress && (
            <div className="card-sm flex items-center gap-3">
              <CloudArrowUpIcon className="w-5 h-5 text-accent animate-pulse" />
              <div className="flex-1 min-w-0">
                <p className="text-sm text-primary truncate">{uploadProgress.name}</p>
                <div className="w-full bg-hover rounded-full h-1.5 mt-1">
                  <div
                    className="h-1.5 rounded-full bg-accent transition-all"
                    style={{ width: `${uploadProgress.percent || 100}%` }}
                  />
                </div>
              </div>
              <span className="text-xs text-muted">Uploading...</span>
            </div>
          )}

          {/* Drop zone overlay */}
          <div
            onDrop={handleDrop}
            onDragOver={handleDragOver}
            onDragLeave={handleDragLeave}
            className={`relative rounded-xl transition-all ${
              dragOver
                ? 'ring-2 ring-accent ring-offset-2 ring-offset-canvas bg-accent/5'
                : ''
            }`}
          >
            {dragOver && (
              <div className="absolute inset-0 z-10 flex flex-col items-center justify-center rounded-xl bg-accent/10 border-2 border-dashed border-accent pointer-events-none">
                <CloudArrowUpIcon className="w-12 h-12 text-accent mb-2" />
                <p className="text-accent font-medium">Drop files to upload</p>
              </div>
            )}

            {loadingFiles ? (
              <LoadingSpinner text="Loading files..." />
            ) : sortedFiles.length === 0 ? (
              <div className="card text-center py-16">
                <FolderIcon className="w-16 h-16 text-muted mx-auto mb-4" />
                <h3 className="text-lg font-semibold text-primary mb-2">This folder is empty</h3>
                <p className="text-sm text-secondary mb-6">Upload files or create a new folder to get started.</p>
                <div className="flex items-center justify-center gap-3">
                  <button onClick={() => fileInputRef.current?.click()} className="btn-primary">
                    <ArrowUpTrayIcon className="w-4 h-4" />
                    Upload Files
                  </button>
                  <button onClick={() => setShowNewFolder(true)} className="btn-secondary">
                    <FolderPlusIcon className="w-4 h-4" />
                    New Folder
                  </button>
                </div>
              </div>
            ) : viewMode === 'list' ? (
              /* ── List View ── */
              <div className="card overflow-hidden p-0">
                <table className="w-full">
                  <thead>
                    <tr className="border-b border-border">
                      <th className="table-header">Name</th>
                      <th className="table-header w-28">Size</th>
                      <th className="table-header w-44">Modified</th>
                      <th className="table-header w-36 text-right">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {/* Back row */}
                    {currentPath !== '/' && (
                      <tr
                        className="table-row cursor-pointer"
                        onClick={navigateUp}
                      >
                        <td className="table-cell" colSpan={4}>
                          <div className="flex items-center gap-3 text-secondary hover:text-primary transition-colors">
                            <FolderIcon className="w-5 h-5 text-accent" />
                            <span className="font-medium">..</span>
                          </div>
                        </td>
                      </tr>
                    )}
                    {sortedFiles.map((item) => {
                      const Icon = fileIcon(item.name, item.directory)
                      const color = iconColor(item.name, item.directory)
                      return (
                        <tr
                          key={item.name}
                          className="table-row cursor-pointer"
                          onClick={() => handleFileClick(item)}
                        >
                          <td className="table-cell">
                            <div className="flex items-center gap-3">
                              <div
                                className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0"
                                style={{ background: `${color}18` }}
                              >
                                <Icon className="w-4 h-4" style={{ color }} />
                              </div>
                              <span className="text-primary font-medium truncate">
                                {item.name}
                              </span>
                              {item.directory && (
                                <span className="badge badge-blue">DIR</span>
                              )}
                            </div>
                          </td>
                          <td className="table-cell text-secondary text-sm font-mono">
                            {item.directory ? '--' : humanSize(item.size)}
                          </td>
                          <td className="table-cell text-secondary text-sm">
                            {item.lastModified
                              ? format(new Date(item.lastModified), 'MMM d, yyyy HH:mm')
                              : '--'}
                          </td>
                          <td className="table-cell text-right">
                            <div className="flex items-center justify-end gap-1" onClick={(e) => e.stopPropagation()}>
                              {!item.directory && (
                                <button
                                  onClick={() => handleDownload(item)}
                                  className="p-1.5 rounded-lg text-secondary hover:text-accent hover:bg-hover transition-colors"
                                  title="Download"
                                  aria-label="Download"
                                >
                                  <ArrowDownTrayIcon className="w-4 h-4" />
                                </button>
                              )}
                              <button
                                onClick={() => openRename(item)}
                                className="p-1.5 rounded-lg text-secondary hover:text-primary hover:bg-hover transition-colors"
                                title="Rename"
                                aria-label="Rename"
                              >
                                <PencilSquareIcon className="w-4 h-4" />
                              </button>
                              <button
                                onClick={() => setDeleteConfirm(item)}
                                className="p-1.5 rounded-lg text-secondary hover:text-red-400 hover:bg-red-500/10 transition-colors"
                                title="Delete"
                                aria-label="Delete"
                              >
                                <TrashIcon className="w-4 h-4" />
                              </button>
                            </div>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            ) : (
              /* ── Grid View ── */
              <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
                {/* Back card */}
                {currentPath !== '/' && (
                  <div
                    onClick={navigateUp}
                    className="card-sm cursor-pointer hover:border-accent/30 transition-all flex flex-col items-center justify-center gap-2 py-6"
                  >
                    <FolderIcon className="w-10 h-10 text-accent" />
                    <span className="text-sm text-secondary font-medium">..</span>
                  </div>
                )}
                {sortedFiles.map((item) => {
                  const Icon = fileIcon(item.name, item.directory)
                  const color = iconColor(item.name, item.directory)
                  return (
                    <div
                      key={item.name}
                      onClick={() => handleFileClick(item)}
                      className="card-sm cursor-pointer hover:border-accent/30 transition-all flex flex-col items-center gap-2 py-5 group relative"
                    >
                      {/* Action buttons on hover */}
                      <div
                        className="absolute top-2 right-2 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity"
                        onClick={(e) => e.stopPropagation()}
                      >
                        {!item.directory && (
                          <button
                            onClick={() => handleDownload(item)}
                            className="p-1 rounded text-secondary hover:text-accent hover:bg-hover transition-colors"
                            title="Download"
                            aria-label="Download"
                          >
                            <ArrowDownTrayIcon className="w-3.5 h-3.5" />
                          </button>
                        )}
                        <button
                          onClick={() => openRename(item)}
                          className="p-1 rounded text-secondary hover:text-primary hover:bg-hover transition-colors"
                          title="Rename"
                          aria-label="Rename"
                        >
                          <PencilSquareIcon className="w-3.5 h-3.5" />
                        </button>
                        <button
                          onClick={() => setDeleteConfirm(item)}
                          className="p-1 rounded text-secondary hover:text-red-400 hover:bg-red-500/10 transition-colors"
                          title="Delete"
                          aria-label="Delete"
                        >
                          <TrashIcon className="w-3.5 h-3.5" />
                        </button>
                      </div>

                      <div
                        className="w-12 h-12 rounded-xl flex items-center justify-center"
                        style={{ background: `${color}18` }}
                      >
                        <Icon className="w-6 h-6" style={{ color }} />
                      </div>
                      <span className="text-sm text-primary font-medium text-center truncate w-full px-1">
                        {item.name}
                      </span>
                      <span className="text-xs text-muted font-mono">
                        {item.directory ? 'Folder' : humanSize(item.size)}
                      </span>
                    </div>
                  )
                })}
              </div>
            )}
          </div>

          {/* Summary bar */}
          {sortedFiles.length > 0 && (
            <div className="flex items-center gap-4 text-xs text-muted px-1">
              <span>{sortedFiles.filter(f => f.directory).length} folders</span>
              <span>{sortedFiles.filter(f => !f.directory).length} files</span>
              <span>
                Total: {humanSize(sortedFiles.filter(f => !f.directory).reduce((s, f) => s + (f.size || 0), 0))}
              </span>
            </div>
          )}
        </>
      )}

      {/* ── New Folder Modal ── */}
      {showNewFolder && (
        <Modal title="New Folder" onClose={() => { setShowNewFolder(false); setNewFolderName('') }} size="sm">
          <div className="space-y-4">
            <div>
              <label className="text-sm font-medium text-secondary mb-1 block">Folder Name</label>
              <input
                value={newFolderName}
                onChange={(e) => setNewFolderName(e.target.value)}
                placeholder="my-folder"
                autoFocus
                onKeyDown={(e) => { if (e.key === 'Enter') handleCreateFolder() }}
              />
            </div>
            <div className="flex justify-end gap-3">
              <button onClick={() => { setShowNewFolder(false); setNewFolderName('') }} className="btn-secondary">
                Cancel
              </button>
              <button
                onClick={handleCreateFolder}
                disabled={!newFolderName.trim() || mkdirMut.isPending}
                className="btn-primary"
              >
                <FolderPlusIcon className="w-4 h-4" />
                {mkdirMut.isPending ? 'Creating...' : 'Create'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* ── Rename Modal ── */}
      {renamingItem && (
        <Modal title="Rename" onClose={() => { setRenamingItem(null); setRenameValue('') }} size="sm">
          <div className="space-y-4">
            <div>
              <label className="text-sm font-medium text-secondary mb-1 block">New Name</label>
              <input
                value={renameValue}
                onChange={(e) => setRenameValue(e.target.value)}
                autoFocus
                onKeyDown={(e) => { if (e.key === 'Enter') handleRenameSubmit() }}
              />
            </div>
            <div className="flex justify-end gap-3">
              <button onClick={() => { setRenamingItem(null); setRenameValue('') }} className="btn-secondary">
                Cancel
              </button>
              <button
                onClick={handleRenameSubmit}
                disabled={!renameValue.trim() || renameValue === renamingItem.name || renameMut.isPending}
                className="btn-primary"
              >
                <PencilSquareIcon className="w-4 h-4" />
                {renameMut.isPending ? 'Renaming...' : 'Rename'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* ── Delete Confirmation ── */}
      <ConfirmDialog
        open={!!deleteConfirm}
        variant="danger"
        title={deleteConfirm ? `Delete ${deleteConfirm.directory ? 'folder' : 'file'}?` : 'Delete?'}
        message={deleteConfirm
          ? `Are you sure you want to delete "${deleteConfirm.name}"? ${deleteConfirm.directory ? 'This will delete the folder and all its contents. ' : ''}This action cannot be undone.`
          : ''}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        loading={deleteMut.isPending}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteConfirm(null)}
      />
    </div>
  )
}
