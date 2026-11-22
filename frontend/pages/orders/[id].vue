<script setup lang="ts">
const route = useRoute(), id = route.params.id, o = ref<any>(null); let tries = 0
async function load() { o.value = await api('/orders/' + id) }
async function sync() { try { await api('/orders/' + id + '/sync', { method: 'POST', quiet: true }) } catch {} await load() }
async function cancel() { await api('/orders/' + id + '/cancel', { method: 'POST' }); notify('Order cancelled'); await load() }
onMounted(async () => { await load()
  if (o.value.order.status === 'pending' && route.query.paid) { await sync(); const t = setInterval(async () => { await sync(); if (o.value.order.status !== 'pending' || ++tries > 8) clearInterval(t) }, 3000) } })
definePageMeta({ middleware: 'guard' })
</script>
<template>
  <p v-if="!o"><span class="spin" /> Loading order…</p>
  <div v-else style="max-width:720px;margin:auto">
    <p class="kicker">Order #{{ o.order.id }}</p><h1 class="h2">{{ o.order.title }}</h1>
    <p class="muted">{{ o.order.qty }} × {{ o.order.tier }} · {{ peso(o.order.amount_centavos) }} · {{ fmt(o.order.starts_at) }}</p>
    <div class="card pad" style="margin:16px 0">
      <span v-if="o.order.status === 'pending'"><span class="spin" /> Awaiting payment confirmation… <button class="btn" style="margin-left:10px" @click="cancel">Cancel order</button></span>
      <b v-else-if="o.order.status === 'paid'">✓ Payment received - tickets emailed to you.</b>
      <b v-else>Order cancelled.</b>
    </div>
    <div v-for="k in o.tickets" :key="k.uuid" class="ticket"><div class="pad"><p class="kicker">Admit one · {{ k.status }}</p><p class="mono" style="font-size:.7rem;word-break:break-all">{{ k.uuid }}</p></div><img class="qr" :src="'/api/qr/' + k.uuid" alt="QR"></div>
    <NuxtLink to="/tickets" class="btn" style="margin-top:14px">All my tickets</NuxtLink>
  </div>
</template>
