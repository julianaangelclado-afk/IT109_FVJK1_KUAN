export default defineNuxtPlugin(async () => {
  const u = useUser(); const tk = localStorage.getItem('tk')
  if (tk) { try { u.value = await $fetch('/api/me', { headers: { Authorization: 'Bearer ' + tk } }) } catch { localStorage.removeItem('tk') } }
})
