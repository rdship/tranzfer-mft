# FTP-Web UI

> Simple, clean web-based file manager for end users with drag-and-drop upload.

**Port:** 3001 | **Stack:** React 18 + Vite + TailwindCSS | **Required:** Optional

---

## Overview

The FTP-Web UI is a lightweight file browser for end users who need to upload and download files through a web browser instead of SFTP/FTP clients. It provides:

- **Directory navigation** with breadcrumb trails
- **Drag-and-drop file upload** with progress tracking
- **File download** with browser standard dialog
- **File and folder management** (create, rename, delete)
- **File type icons** (images, PDFs, documents, spreadsheets, archives, EDI, encrypted)
- **Auto-logout** on token expiry

---

## Quick Start

### Development
```bash
cd ftp-web-ui
npm install
npm run dev
# Opens http://localhost:3001
```

### Production
```bash
docker compose up -d ftp-web-service ftp-web-ui
# Opens http://localhost:3001 or via api-gateway at /portal/
```

---

## Pages

| Page | Path | Description |
|------|------|-------------|
| **Login** | `/login` | User authentication |
| **File Manager** | `/` | Main file browser interface |

---

## Features

### File Browser
- Navigate directories with click-through and breadcrumbs
- Sort files by name, size, or date
- File size formatting (B/KB/MB/GB)
- File type detection and icons

### Upload
- Drag-and-drop files onto the browser
- Click to select files
- Batch upload with progress tracking
- Max file size: 512 MB (server-configurable)
- Toast notifications on success/failure

### Download
- Click to download any file
- Standard browser download dialog

### Management
- Create new directories
- Rename files and folders
- Delete with confirmation dialog
- Recursive directory deletion

---

## Tech Stack

| Technology | Purpose |
|-----------|---------|
| React 18 | UI framework |
| Vite | Build tool |
| React Router | Routing (Login / FileManager) |
| Axios | HTTP client (talks to ftp-web-service) |
| TailwindCSS | Styling |
| Heroicons | Icons |
| react-hot-toast | Notifications |

---

## Project Structure

```
ftp-web-ui/
├── package.json
├── vite.config.js
├── tailwind.config.js
├── nginx.conf              ← Production config
├── Dockerfile
└── src/
    ├── App.jsx
    ├── main.jsx
    ├── pages/
    │   ├── Login.jsx       ← Authentication page
    │   └── FileManager.jsx ← Main file browser
    └── components/
        └── ...
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_URL` | `http://localhost:8083` | ftp-web-service API URL |
| Port (dev) | `3001` | Vite dev server port |
| Port (prod) | `80` | Nginx port in Docker |

---

## Development

```bash
npm install
npm run dev       # Hot reload on :3001
npm run build     # Production build → dist/
npm run lint      # ESLint
```

---

## Dependencies

- **ftp-web-service** — Backend API for file operations
- **onboarding-api** — User authentication (JWT tokens)
