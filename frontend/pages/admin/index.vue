<script setup lang="ts">
const evs = ref<any[]>([]), users = ref<any[]>([]), sales = ref<any[]>([]), cache = ref<any>(null), me = useUser()
async function load() { evs.value = await api('/admin/events'); users.value = await api('/admin/users'); sales.value = await api('/org/sales'); cache.value = await api('/admin/cache') }
async function setSt(e: any, status: string) { await api(`/admin/events/${e.id}/status`, { method: 'POST', body: { status } }); notify('Event ' + status); await load() }
async function setRole(u: any, ev: Event) { await api(`/admin/users/${u.id}/role`, { method: 'PUT', body: { role: (ev.target as HTMLSelectElement).value } }); notify('Role updated'); await load() }
const total = computed(() => sales.value.reduce((a, s) => a + Number(s.revenue), 0))
onMounted(load); definePageMeta({ middleware: 'guard', roles: ['admin'] })
</script>
<template>
  <p class="kicker">Student Affairs</p><h1 class="h2">Admin Office</h1><hr class="rule2">
  <div class="grid2" style="margin:16px 0"><div class="card pad"><p class="kicker">University revenue</p><h2 class="h2">{{ peso(total) }}</h2></div>
    <div v-if="cache" class="card pad"><p class="kicker">Cache (60s expiry)</p><p class="mono" style="font-size:.8rem">{{ cache.entries }} entries · {{ cache.hits }} hits · {{ cache.misses }} misses<br>{{ cache.keys.join(', ') }}</p></div></div>
  <h2 class="h2">Event approvals</h2>
  <table><tr><th>Event</th><th>Organizer</th><th>Status</th><th /></tr>
    <tr v-for="e in evs" :key="e.id"><td>{{ e.title }}<br><small class="muted">{{ fmt(e.starts_at) }}</small></td><td>{{ e.organizer }}</td><td>{{ e.status }}</td>
      <td><span v-if="e.status !== 'published'" class="tag" @click="setSt(e, 'published')">Approve</span><span v-if="e.status === 'pending'" class="tag" @click="setSt(e, 'rejected')">Reject</span><span v-if="e.status === 'published'" class="tag" @click="setSt(e, 'cancelled')">Cancel</span></td></tr></table>
  <h2 class="h2" style="margin-top:30px">Sales by event</h2>
  <table><tr><th>Event</th><th>Sold</th><th>Admitted</th><th>Revenue</th></tr><tr v-for="s in sales" :key="s.id"><td>{{ s.title }}</td><td>{{ s.sold }}</td><td>{{ s.admitted }}</td><td>{{ peso(s.revenue) }}</td></tr></table>
  <h2 class="h2" style="margin-top:30px">Users & roles</h2>
  <table><tr><th>Name</th><th>Email</th><th>Role</th></tr>
    <tr v-for="u in users" :key="u.id"><td>{{ u.name }}</td><td>{{ u.email }}</td><td><select class="input" style="max-width:150px" :value="u.role" :disabled="u.id === me.id" @change="setRole(u, $event)"><option>attendee</option><option>organizer</option><option>admin</option></select></td></tr></table>
</template>
