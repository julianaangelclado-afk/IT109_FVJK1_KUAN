<script setup lang="ts">
const code = ref(''), res = ref<any>(null), on = ref(false); let sc: any = null, lock = false
async function verify(t: string) {
  if (lock) return; lock = true; setTimeout(() => (lock = false), 2200)
  try { const r = await api('/tickets/verify', { method: 'POST', body: { ticket_uuid: t }, quiet: true }); res.value = { ok: true, msg: `ADMIT · ${r.holder} · ${r.event} (${r.tier})` } }
  catch (e: any) { res.value = { ok: false, msg: (e?.data?.error || 'Invalid ticket').toUpperCase() } }
}
async function start() { const { Html5Qrcode } = await import('html5-qrcode'); sc = new Html5Qrcode('reader'); on.value = true
  try { await sc.start({ facingMode: 'environment' }, { fps: 10, qrbox: 240 }, (t: string) => verify(t), () => {}) } catch { on.value = false; notify('Camera unavailable - use manual entry', 'err') } }
async function stop() { try { await sc?.stop() } catch {} on.value = false }
onBeforeUnmount(stop); definePageMeta({ middleware: 'guard', roles: ['organizer', 'admin'] })
</script>
<template>
  <div style="max-width:560px;margin:auto"><p class="kicker">At the door</p><h1 class="h2">Ticket Scanner</h1><hr class="rule2">
    <div id="reader" style="width:100%;margin:14px 0;border:1px solid var(--ink)" />
    <button class="btn solid" @click="on ? stop() : start()">{{ on ? 'Stop camera' : 'Start camera' }}</button>
    <label class="label">Or enter ticket code</label><div style="display:flex;gap:8px"><input v-model="code" class="input" placeholder="xxxxxxxx-xxxx-…"><button class="btn" @click="verify(code)">Verify</button></div>
    <div v-if="res" class="card pad mono" :style="{ marginTop: '16px', textAlign: 'center', fontSize: '1.05rem', borderColor: res.ok ? '#1e7d32' : '#c0392b', borderWidth: '3px' }">{{ res.ok ? '✓' : '✗' }} {{ res.msg }}</div></div>
</template>
