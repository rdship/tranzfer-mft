import { useCallback } from 'react'
import toast from 'react-hot-toast'

/**
 * Hook for consistent API error handling across all pages.
 * Parses the standardized ApiError response from the backend.
 *
 * Usage:
 *   const { handleError } = useApiError()
 *   try { await api.call() } catch(e) { handleError(e) }
 */
export default function useApiError() {
  const handleError = useCallback((error, fallbackMessage = 'Operation failed') => {
    const response = error?.response

    if (!response) {
      // Network error — no response from server
      toast.error('Network error — please check your connection')
      return
    }

    const data = response.data

    // Handle our standardized ApiError format
    if (data?.code && data?.message) {
      const msg = data.message
      const correlationId = data.correlationId

      switch (data.code) {
        case 'VALIDATION_FAILED':
          toast.error(data.details?.length ? data.details.join('\n') : msg)
          break
        case 'ACCESS_DENIED':
          toast.error('Access denied — you do not have permission for this action')
          break
        case 'SERVICE_UNAVAILABLE':
          toast.error('Service temporarily unavailable — please retry in a moment')
          break
        case 'LICENSE_EXPIRED':
          toast.error('License expired — contact your administrator')
          break
        default:
          toast.error(msg)
      }

      if (correlationId) {
        console.warn(`[API Error] ${data.code}: ${msg} (correlationId: ${correlationId})`)
      }
      return
    }

    // Fallback for non-standard errors
    if (typeof data === 'string') {
      toast.error(data)
    } else if (data?.error) {
      toast.error(data.error)
    } else {
      toast.error(`${fallbackMessage} (${response.status})`)
    }
  }, [])

  return { handleError }
}
