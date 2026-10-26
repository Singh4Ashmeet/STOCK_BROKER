/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './src/pages/**/*.{js,ts,jsx,tsx,mdx}',
    './src/components/**/*.{js,ts,jsx,tsx,mdx}',
    './src/app/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {
      colors: {
        primary: {
          50: '#f7f1e5',
          100: '#f0e3c8',
          200: '#e5d0a5',
          300: '#d7bd83',
          400: '#d0b782',
          500: '#c2a46d',
          600: '#aa8d58',
          700: '#876d43',
          800: '#5f4b2e',
          900: '#392c1a',
        },
        surface: {
          50: '#f0eee7',
          100: '#d9d9cf',
          200: '#b9baaf',
          300: '#92948a',
          400: '#6e7068',
          500: '#505247',
          600: '#383a32',
          700: '#2a2c26',
          800: '#22241f',
          900: '#1c1e1a',
          950: '#171916',
        },
        positive: '#8ba58b',
        negative: '#c77c73',
        warning: '#d5aa68',
        info: '#b5b7ac',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        display: ['Plus Jakarta Sans', 'Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
      },
      animation: {
        'pulse-slow': 'pulse 3s cubic-bezier(0.4, 0, 0.6, 1) infinite',
      },
    },
  },
  plugins: [],
};
