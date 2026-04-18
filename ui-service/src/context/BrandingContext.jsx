import { createContext, useContext, useState, useEffect } from 'react'

const defaults = {
  // R127: the default was "TranzFer Command Center" which clipped in the
  // sidebar at 1280×720 (tester's UX review flagged it as a polish bug).
  // Shortened to match the top-nav "TranzFer MFT" — one brand, not two.
  companyName: 'TranzFer MFT',
  logoUrl: '',
  primaryColor: '#3b82f6',
  accentColor: '#2563eb',
  faviconUrl: ''
}

const BrandingContext = createContext(defaults)

export function BrandingProvider({ children }) {
  const [branding, setBranding] = useState(() => {
    try { return { ...defaults, ...JSON.parse(localStorage.getItem('branding') || '{}') } }
    catch { return defaults }
  })

  useEffect(() => {
    document.title = branding.companyName
    // Apply CSS variables
    const root = document.documentElement
    // Convert hex to use in CSS vars for tailwind brand colors
    root.style.setProperty('--brand-500', branding.primaryColor)
    root.style.setProperty('--brand-600', branding.accentColor)
  }, [branding])

  const updateBranding = (updates) => {
    const next = { ...branding, ...updates }
    setBranding(next)
    localStorage.setItem('branding', JSON.stringify(next))
  }

  return (
    <BrandingContext.Provider value={{ branding, updateBranding }}>
      {children}
    </BrandingContext.Provider>
  )
}

export const useBranding = () => useContext(BrandingContext)
