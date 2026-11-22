<script setup lang="ts">
const events = ref<any[]>([]), q = ref(''), cat = ref('All'), loading = ref(true)
onMounted(async () => { try { events.value = await api('/events') } finally { loading.value = false } })
const cats = computed(() => ['All', ...new Set(events.value.map((e) => e.category))])
const list = computed(() => events.value.filter((e) => (cat.value === 'All' || e.category === cat.value) && (e.title + e.venue).toLowerCase().includes(q.value.toLowerCase())))
</script>
<template>
  <section style="text-align:center;padding:26px 0 10px">
    <p class="kicker">Intramurals · Pageants · Theatre · Concerts</p>
    <h1 class="h1">An evening worth<br><em>keeping a ticket for.</em></h1>
    <p class="muted" style="max-width:560px;margin:14px auto">Reserve passes to university-wide events, pay by GCash, and receive a QR pass by email. Gift a seat to a friend when plans change.</p>
  </section>
  <hr class="rule2">
  <div style="display:flex;gap:16px;flex-wrap:wrap;align-items:center;margin:16px 0">
    <input v-model="q" class="input" style="max-width:320px" placeholder="Search events or venues…">
    <div><span v-for="c in cats" :key="c" :class="['tag', cat === c && 'on']" @click="cat = c">{{ c }}</span></div>
  </div>
  <p v-if="loading"><span class="spin" /> Loading events…</p>
  <p v-else-if="!list.length" class="muted">No events published yet. Check back soon.</p>
  <div class="grid3">
    <NuxtLink v-for="e in list" :key="e.id" :to="'/events/' + e.id" class="card">
      <img v-if="e.poster_url" :src="e.poster_url" class="poster" alt=""><div v-else class="noposter disp">{{ e.title.charAt(0) }}</div>
      <div class="pad"><span class="tag">{{ e.category }}</span>
        <h3 style="font-size:1.35rem;line-height:1.2">{{ e.title }}</h3>
        <p class="muted" style="font-size:.85rem">{{ fmt(e.starts_at) }}<br>{{ e.venue }}</p>
        <p class="mono" style="margin-top:8px">{{ e.min_price == null ? 'No tiers yet' : 'From ' + peso(e.min_price) }}</p></div>
    </NuxtLink>
  </div>
</template>
