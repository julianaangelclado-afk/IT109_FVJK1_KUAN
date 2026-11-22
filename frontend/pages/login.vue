<script setup lang="ts">
const cfg = useRuntimeConfig(), route = useRoute(), user = useUser(), busy = ref(false)
async function done(r: any) {
  busy.value = true
  try { const x = await api('/auth/google', { method: 'POST', body: { credential: r.credential } }); localStorage.setItem('tk', x.token); user.value = x.user; notify('Welcome, ' + x.user.name); navigateTo((route.query.next as string) || '/') }
  finally { busy.value = false }
}
onMounted(() => {
  const s = document.createElement('script'); s.src = 'https://accounts.google.com/gsi/client'; s.async = true
  s.onload = () => { (window as any).google.accounts.id.initialize({ client_id: cfg.public.googleClientId, callback: done }); (window as any).google.accounts.id.renderButton(document.getElementById('gbtn'), { theme: 'outline', size: 'large', shape: 'rectangular' }) }
  document.head.appendChild(s)
})
</script>
<template>
  <div class="card pad" style="max-width:460px;margin:40px auto;text-align:center">
    <p class="kicker">Members' Entrance</p><h1 class="h2">Sign in to continue</h1><hr class="rule2">
    <p class="muted">Use your Google account. New visitors are registered automatically as attendees.</p>
    <div id="gbtn" style="display:flex;justify-content:center;margin:22px 0" />
    <p v-if="busy"><span class="spin" /> Verifying with Google…</p>
  </div>
</template>
