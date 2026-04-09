export default {
  content: ['./index.html', './src/**/*.{js,jsx,ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // ── Semantic theme tokens — resolve via CSS vars set by [data-theme] on layout root
        surface:        'rgb(var(--surface)      / <alpha-value>)',
        canvas:         'rgb(var(--canvas)       / <alpha-value>)',
        border:         'rgb(var(--border)       / <alpha-value>)',
        hover:          'rgb(var(--hover)        / <alpha-value>)',
        primary:        'rgb(var(--tx-primary)   / <alpha-value>)',
        secondary:      'rgb(var(--tx-secondary) / <alpha-value>)',
        muted:          'rgb(var(--tx-muted)     / <alpha-value>)',
        accent:         'rgb(var(--accent)       / <alpha-value>)',
        'accent-soft':  'rgb(var(--accent-soft)  / <alpha-value>)',
        'chart-1':      'rgb(var(--chart-1)      / <alpha-value>)',
        'chart-2':      'rgb(var(--chart-2)      / <alpha-value>)',
        'chart-3':      'rgb(var(--chart-3)      / <alpha-value>)',
        'chart-4':      'rgb(var(--chart-4)      / <alpha-value>)',
        // ── Static brand (for sidebar, login gradient — always same) ──
        brand: {
          50:  '#eff6ff',
          100: '#dbeafe',
          500: '#3b82f6',
          600: '#2563eb',
          700: '#1d4ed8',
          900: '#1e3a8a',
        },
      },
      fontFamily: {
        sans: ['Inter', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'Consolas', 'monospace'],
      },
      boxShadow: {
        glow:          '0 0 20px rgb(var(--accent) / 0.30)',
        'glow-sm':     '0 0 10px rgb(var(--accent) / 0.20)',
        'glow-lg':     '0 0 40px rgb(var(--accent) / 0.40)',
        card:          '0 1px 3px rgb(0 0 0 / 0.05), 0 1px 2px -1px rgb(0 0 0 / 0.05)',
        'card-hover':  '0 8px 24px rgb(0 0 0 / 0.10), 0 2px 8px -2px rgb(0 0 0 / 0.06)',
        'card-dark':   '0 1px 3px rgb(0 0 0 / 0.30), 0 1px 2px -1px rgb(0 0 0 / 0.20)',
        panel:         '0 24px 64px -12px rgb(0 0 0 / 0.28)',
        'inner-top':   'inset 0 1px 0 rgb(255 255 255 / 0.06)',
      },
      keyframes: {
        'slide-up': {
          from: { opacity: '0', transform: 'translateY(10px)' },
          to:   { opacity: '1', transform: 'translateY(0)' },
        },
        'fade-in': {
          from: { opacity: '0' },
          to:   { opacity: '1' },
        },
        'scale-in': {
          from: { opacity: '0', transform: 'scale(0.96)' },
          to:   { opacity: '1', transform: 'scale(1)' },
        },
        shimmer: {
          '0%':   { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition:  '200% 0' },
        },
        'pulse-dot': {
          '0%, 100%': { opacity: '1',   transform: 'scale(1)' },
          '50%':       { opacity: '0.4', transform: 'scale(0.8)' },
        },
        float: {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%':       { transform: 'translateY(-20px)' },
        },
        drift: {
          '0%, 100%': { transform: 'translate(0, 0) scale(1)' },
          '33%':       { transform: 'translate(30px, -20px) scale(1.05)' },
          '66%':       { transform: 'translate(-20px, 10px) scale(0.95)' },
        },
      },
      animation: {
        'slide-up':   'slide-up 0.20s ease-out',
        'fade-in':    'fade-in 0.15s ease-out',
        'scale-in':   'scale-in 0.18s ease-out',
        shimmer:      'shimmer 1.6s linear infinite',
        'pulse-dot':  'pulse-dot 2.2s ease-in-out infinite',
        float:        'float 7s ease-in-out infinite',
        drift:        'drift 12s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}
