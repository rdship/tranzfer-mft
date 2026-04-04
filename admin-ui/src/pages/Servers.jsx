import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { onboardingApi, configApi, gatewayApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import { PlusIcon, TrashIcon } from '@heroicons/react/24/outline'
import { useState } from 'react'
import toast from 'react-hot-toast'
import Modal from '../components/Modal'
import EmptyState from '../components/EmptyState'

export default function Servers() {
  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">Servers</h1>
        <p className="text-gray-500 text-sm">Manage Servers configuration</p></div>
      <div className="card">
        <EmptyState title="Servers" description="Configuration coming soon" />
      </div>
    </div>
  )
}
