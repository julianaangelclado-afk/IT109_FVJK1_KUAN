<script setup lang="ts">
const route = useRoute(), user = useUser()
const d = ref<any>(null), qty = ref(1), cap = ref<any>({}), ans = ref(''), busy = ref(0), now = ref(Date.now())
async function loadCap() { cap.value = await api('/captcha'); ans.value = '' }
onMounted(async () => { d.value = await api('/events/' + route.params.id); await loadCap(); setInterval(() => (now.value = Date.now()), 1000) })
const left = computed(() => { if (!d.value) return null; const ms = new Date(d.value.event.starts_at).getTime() - now.value; if (ms <= 0) return null
  return { d: Math.floor(ms / 864e5), h: Math.floor(ms / 36e5) % 24, m: Math.floor(ms / 6e4) % 60, s: Math.floor(ms / 1e3) % 60 } })
async function buy(t: any) {
  if (!user.value) return navigateTo('/login?next=' + route.fullPath)
  busy.value = t.id
  try { const r = await api('/checkout', { method: 'POST', body: { tierId: t.id, qty: qty.value, captchaId: cap.value.id, captchaAnswer: ans.value } })
    notify('Order created - ' + (r.checkoutUrl ? 'redirecting to PayMongo…' : 'your tickets are ready'))
    if (r.checkoutUrl) location.href = r.checkoutUrl; else navigateTo('/orders/' + r.orderId) }
  catch { await loadCap() } finally { busy.value = 0 }
}
function ics() { const e = d.value.event, z = (s: string) => s.replace(/[-:]/g, '').slice(0, 13) + '00'
  const t = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'BEGIN:VEVENT', 'UID:evt' + e.id + '@campus-tickets', 'DTSTART:' + z(e.starts_at), 'SUMMARY:' + e.title, 'LOCATION:' + e.venue, 'END:VEVENT', 'END:VCALENDAR'].join('\r\n')
  const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([t], { type: 'text/calendar' })); a.download = 'event.ics'; a.click() }
</script>
<template>
  <p v-if="!d"><span class="spin" /> Loading…</p>
  <div v-else class="grid2" style="grid-template-columns:minmax(0,1.5fr) minmax(0,1fr);align-items:start">
    <div>
      <img v-if="d.event.poster_url" :src="d.event.poster_url" class="poster" style="border:1px solid var(--ink)" alt="">
      <span class="tag" style="margin-top:14px">{{ d.event.category }}</span>
      <h1 class="h1" style="font-size:2.6rem">{{ d.event.title }}</h1>
      <p class="muted">{{ fmt(d.event.starts_at) }} · {{ d.event.venue }} · hosted by {{ d.event.organizer }}</p>
      <hr class="rule"><p style="white-space:pre-line">{{ d.event.description }}</p>
      <div v-if="left" class="card pad mono" style="margin-top:18px;text-align:center">Doors open in {{ left.d }}d {{ left.h }}h {{ left.m }}m {{ left.s }}s</div>
      <button class="btn" style="margin-top:14px" @click="ics">+ Add to calendar</button>
    </div>
    <aside class="card pad">
      <p class="kicker">Select your pass</p><hr class="rule2">
      <p v-if="!d.tiers.length" class="muted">Tickets are not on sale yet.</p>
      <label class="label">Quantity</label><input v-model.number="qty" type="number" min="1" max="10" class="input">
      <label class="label">Human check · {{ cap.question }}</label><input v-model="ans" class="input" inputmode="numeric" placeholder="Your answer">
      <div v-for="t in d.tiers" :key="t.id" style="margin-top:14px;border-top:1px dashed var(--ink);padding-top:12px">
        <div style="display:flex;justify-content:space-between"><h3 style="font-size:1.2rem">{{ t.name }}</h3><b class="mono">{{ peso(t.price_centavos) }}</b></div>
        <p class="muted" style="font-size:.8rem">{{ t.remaining > 0 ? t.remaining + ' of ' + t.capacity + ' left' : 'Sold out' }}</p>
        <button class="btn solid" :disabled="t.remaining < qty || busy === t.id" style="margin-top:6px" @click="buy(t)"><span v-if="busy === t.id" class="spin" /> {{ busy === t.id ? 'Processing' : 'Get tickets' }}</button>
      </div>
    </aside>
  </div>
</template>
