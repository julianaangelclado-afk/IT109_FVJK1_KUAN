<script setup lang="ts">
const evs = ref<any[]>([]), sales = ref<any[]>([]), busy = ref(false), up = ref(false), editId = ref(0)
const blank = () => ({ title: '', description: '', venue: '', startsAt: '', category: 'Concert', posterUrl: '' })
const form = reactive<any>(blank()), tf = reactive<Record<number, any>>({})
async function load() { evs.value = await api('/org/events'); sales.value = await api('/org/sales'); evs.value.forEach((e) => (tf[e.id] ||= { name: '', price: '', capacity: '' })) }
const sale = (id: number) => sales.value.find((s) => s.id === id)
async function save() {
  if (!/^[\p{L}\p{N} .,:;'&!?()\-]{3,120}$/u.test(form.title)) return notify('Title: 3-120 letters/numbers', 'err')
  if (!/^[\p{L}\p{N} .,:;'&!?()\-]{3,120}$/u.test(form.venue)) return notify('Venue: 3-120 characters', 'err')
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(form.startsAt)) return notify('Pick a date and time', 'err')
  busy.value = true
  try { await api(editId.value ? '/org/events/' + editId.value : '/org/events', { method: editId.value ? 'PUT' : 'POST', body: form }); notify(editId.value ? 'Event updated' : 'Event submitted for approval'); Object.assign(form, blank()); editId.value = 0; await load() }
  finally { busy.value = false }
}
function edit(e: any) { editId.value = e.id; Object.assign(form, { title: e.title, description: e.description || '', venue: e.venue, startsAt: e.starts_at.slice(0, 16), category: e.category, posterUrl: e.poster_url || '' }); scrollTo({ top: 0, behavior: 'smooth' }) }
async function cancelEv(e: any) { if (confirm('Cancel this event?')) { await api('/org/events/' + e.id, { method: 'DELETE' }); await load() } }
async function addTier(e: any) {
  const t = tf[e.id]; if (!/^\d+(\.\d{1,2})?$/.test(String(t.price))) return notify('Price like 150 or 150.50 (0 = free)', 'err')
  if (!/^[1-9]\d{0,4}$/.test(String(t.capacity))) return notify('Capacity must be a whole number', 'err')
  await api(`/org/events/${e.id}/tiers`, { method: 'POST', body: t }); tf[e.id] = { name: '', price: '', capacity: '' }; notify('Tier added'); await load() }
async function editTier(t: any) { const price = prompt('Price in pesos', String(t.price_centavos / 100)); const cap = prompt('Capacity', String(t.capacity)); if (price == null || cap == null) return
  await api('/org/tiers/' + t.id, { method: 'PUT', body: { name: t.name, price, capacity: cap } }); await load() }
async function delTier(t: any) { if (confirm('Delete tier?')) { await api('/org/tiers/' + t.id, { method: 'DELETE' }); await load() } }
async function upload(ev: Event) {
  const file = (ev.target as HTMLInputElement).files?.[0]; if (!file) return; up.value = true
  try { const s = await api('/upload/sign'); const fd = new FormData(); fd.append('file', file); fd.append('api_key', s.apiKey); fd.append('timestamp', s.timestamp); fd.append('signature', s.signature); fd.append('folder', s.folder)
    const r = await $fetch<any>(`https://api.cloudinary.com/v1_1/${s.cloudName}/image/upload`, { method: 'POST', body: fd }); form.posterUrl = r.secure_url; notify('Poster uploaded') }
  catch { notify('Upload failed - check Cloudinary settings', 'err') } finally { up.value = false } }
onMounted(load); definePageMeta({ middleware: 'guard', roles: ['organizer', 'admin'] })
</script>
<template>
  <p class="kicker">Box office</p><h1 class="h2">Organizer Desk</h1><hr class="rule2">
  <div class="card pad" style="margin:16px 0"><h3 style="font-size:1.3rem">{{ editId ? 'Edit event' : 'New event' }}</h3>
    <div class="grid2">
      <div><label class="label">Title</label><input v-model="form.title" class="input"><label class="label">Venue</label><input v-model="form.venue" class="input">
        <label class="label">Date & time</label><input v-model="form.startsAt" type="datetime-local" class="input"><label class="label">Category</label><input v-model="form.category" class="input"></div>
      <div><label class="label">Description</label><textarea v-model="form.description" rows="5" class="input" /><label class="label">Poster (Cloudinary)</label><input type="file" accept="image/*" class="input" @change="upload"><span v-if="up" class="spin" /><img v-if="form.posterUrl" :src="form.posterUrl" style="height:60px;margin-top:6px" alt=""></div>
    </div>
    <button class="btn solid" style="margin-top:14px" :disabled="busy || up" @click="save"><span v-if="busy" class="spin" /> {{ editId ? 'Save changes' : 'Submit for approval' }}</button>
  </div>
  <div v-for="e in evs" :key="e.id" class="card pad" style="margin-bottom:16px">
    <div style="display:flex;justify-content:space-between;flex-wrap:wrap;gap:8px"><div><h3 style="font-size:1.3rem">{{ e.title }}</h3><p class="muted" style="font-size:.82rem">{{ fmt(e.starts_at) }} · {{ e.venue }}</p></div>
      <div><span class="tag on">{{ e.status }}</span><button class="btn" @click="edit(e)">Edit</button> <button v-if="e.status !== 'cancelled'" class="btn" @click="cancelEv(e)">Cancel</button></div></div>
    <p v-if="sale(e.id)" class="mono" style="font-size:.8rem;margin:8px 0">Sold {{ sale(e.id).sold }} · Admitted at door {{ sale(e.id).admitted }} · Revenue {{ peso(sale(e.id).revenue) }}</p>
    <table><tr><th>Tier</th><th>Price</th><th>Capacity</th><th /></tr>
      <tr v-for="t in e.tiers" :key="t.id"><td>{{ t.name }}</td><td>{{ peso(t.price_centavos) }}</td><td>{{ t.capacity }}</td><td><span class="tag" @click="editTier(t)">Edit</span><span class="tag" @click="delTier(t)">Delete</span></td></tr></table>
    <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap"><input v-model="tf[e.id].name" class="input" style="max-width:180px" placeholder="Tier (e.g. VIP)"><input v-model="tf[e.id].price" class="input" style="max-width:130px" placeholder="Price ₱"><input v-model="tf[e.id].capacity" class="input" style="max-width:130px" placeholder="Capacity"><button class="btn" @click="addTier(e)">+ Tier</button></div>
  </div>
</template>
