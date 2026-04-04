export default {
  content: ['./index.html', './src/**/*.{js,jsx,ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          50: 'var(--brand-50, #eff6ff)',
          100: 'var(--brand-100, #dbeafe)',
          500: 'var(--brand-500, #3b82f6)',
          600: 'var(--brand-600, #2563eb)',
          700: 'var(--brand-700, #1d4ed8)',
          900: 'var(--brand-900, #1e3a8a)',
        }
      }
    }
  },
  plugins: []
}
