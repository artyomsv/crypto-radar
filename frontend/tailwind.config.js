/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        background: '#0a0e17',
        surface: '#111827',
        'surface-light': '#1a2332',
        'surface-border': '#1e293b',
        accent: '#06b6d4',
        'accent-light': '#22d3ee',
        gain: '#10b981',
        'gain-light': '#34d399',
        loss: '#ef4444',
        'loss-light': '#f87171',
        muted: '#64748b',
        'text-primary': '#f1f5f9',
        'text-secondary': '#94a3b8',
      },
      fontFamily: {
        mono: ['JetBrains Mono', 'Fira Code', 'monospace'],
      },
    },
  },
  plugins: [],
}
