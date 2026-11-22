<script setup lang="ts">
const tk = ref<any[]>([]), orders = ref<any[]>([]), to = reactive<Record<string, string>>({})
async function load() { tk.value = await api('/tickets'); orders.value = await api('/orders') }
async function give(k: any) { await api('/tickets/' + k.uuid + '/transfer', { method: 'POST', body: { email: to[k.uuid] } }); notify('Ticket sent!'); await load() }
onMounted(load); definePageMeta({ middleware: 'guard' })
</script>
<template>
  <p class="kicker">Your wallet</p><h1 class="h2">My Tickets</h1><hr class="rule2">
  <p v-if="!tk.length" class="muted">No tickets yet.</p>
  <div class="grid2" style="margin-top:14px">
    <div v-for="k in tk" :key="k.uuid" class="ticket">
      <div class="pad"><p class="kicker">{{ k.tier }} · {{ k.status === 'used' ? 'USED' : 'VALID' }}</p><h3 style="font-size:1.25rem">{{ k.title }}</h3>
        <p class="muted" style="font-size:.82rem">{{ fmt(k.starts_at) }}<br>{{ k.venue }}</p>
        <div v-if="k.status === 'paid'" style="display:flex;gap:6px;margin-top:8px"><input v-model="to[k.uuid]" class="input" placeholder="Gift to friend's email"><button class="btn" @click="give(k)">Send</button></div></div>
      <img class="qr" :src="'/api/qr/' + k.uuid" alt="QR">
    </div>
  </div>
  <h2 class="h2" style="margin-top:36px">Order history</h2><hr class="rule">
  <table><tr><th>#</th><th>Event</th><th>Qty</th><th>Total</th><th>Status</th><th /></tr>
    <tr v-for="o in orders" :key="o.id"><td>{{ o.id }}</td><td>{{ o.title }} · {{ o.tier }}</td><td>{{ o.qty }}</td><td>{{ peso(o.amount_centavos) }}</td><td>{{ o.status }}</td><td><NuxtLink :to="'/orders/' + o.id" class="tag">View</NuxtLink></td></tr></table>
</template>
