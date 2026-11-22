export default defineNuxtConfig({
  compatibilityDate: '2024-11-01',
  ssr: false,
  modules: ['@nuxtjs/tailwindcss'],
  css: ['~/assets/main.css'],
  runtimeConfig: { public: { googleClientId: '' } },
  routeRules: { '/api/**': { proxy: 'http://localhost:8080/api/**' } },
  app: { head: { title: 'Campus Tickets · CSU', link: [
    { rel: 'preconnect', href: 'https://fonts.googleapis.com' },
    { rel: 'stylesheet', href: 'https://fonts.googleapis.com/css2?family=IM+Fell+English:ital@0;1&family=Libre+Baskerville:ital,wght@0,400;0,700;1,400&family=Special+Elite&display=swap' } ] } }
})
