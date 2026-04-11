import { createContext, useContext, useEffect, useState, useCallback } from 'react'

/**
 * ThemeContext — controls the `data-theme` attribute on the document
 * root, which drives the CSS variable swap in index.css. The user asked
 * for a "small hidden-but-visible" toggle, so this context exposes a
 * toggle() and the current theme for the <ThemeToggleButton> in the
 * sidebar footer.
 *
 * Persistence: the selected theme is stored in localStorage under the
 * key `tranzfer.theme`. If no value exists on first load we honor the
 * OS-level `prefers-color-scheme` media query — so users on macOS
 * with auto dark mode get it for free.
 *
 * Themes supported: 'dark' (default) and 'light'. More variants can be
 * added later via [data-theme="<name>"] blocks in index.css.
 */

const STORAGE_KEY = 'tranzfer.theme'

const ThemeContext = createContext({
  theme: 'dark',
  setTheme: () => {},
  toggle: () => {},
})

function resolveInitialTheme() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch { /* localStorage may be unavailable in SSR / private mode */ }
  try {
    if (window.matchMedia?.('(prefers-color-scheme: light)').matches) return 'light'
  } catch { /* matchMedia may not exist in very old browsers */ }
  return 'dark'
}

export function ThemeProvider({ children }) {
  const [theme, setThemeState] = useState(resolveInitialTheme)

  // Apply the theme to the <html> element so the CSS var block fires
  // immediately. Also persist to localStorage so the choice survives
  // reload. Wrapped in an effect so the first paint matches the stored
  // value without a flash of wrong theme.
  useEffect(() => {
    const root = document.documentElement
    root.setAttribute('data-theme', theme)
    root.style.colorScheme = theme
    try { localStorage.setItem(STORAGE_KEY, theme) } catch { /* ignore */ }
  }, [theme])

  const setTheme = useCallback((next) => {
    if (next === 'light' || next === 'dark') setThemeState(next)
  }, [])

  const toggle = useCallback(() => {
    setThemeState(t => (t === 'dark' ? 'light' : 'dark'))
  }, [])

  return (
    <ThemeContext.Provider value={{ theme, setTheme, toggle }}>
      {children}
    </ThemeContext.Provider>
  )
}

export const useTheme = () => useContext(ThemeContext)
