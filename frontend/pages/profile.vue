<script setup lang="ts">
const user = useUser(), P = 'https://psgc.gitlab.io/api'
const f = reactive<any>({ name: '', phone: '', regionCode: '', region: '', provinceCode: '', province: '', cityCode: '', city: '', barangayCode: '', barangay: '' })
const regions = ref<any[]>([]), provinces = ref<any[]>([]), cities = ref<any[]>([]), brgys = ref<any[]>([]), busy = ref(false)
const get = async (p: string) => { try { return await $fetch<any[]>(P + p) } catch { notify('PSGC service unavailable', 'err'); return [] } }
const nm = (a: any[], c: string) => a.find((x) => x.code === c)?.name || ''
async function onRegion() { f.region = nm(regions.value, f.regionCode); f.provinceCode = f.cityCode = f.barangayCode = ''; f.province = f.city = f.barangay = ''; provinces.value = await get(`/regions/${f.regionCode}/provinces/`); cities.value = provinces.value.length ? [] : await get(`/regions/${f.regionCode}/cities-municipalities/`); brgys.value = [] }
async function onProv() { f.province = nm(provinces.value, f.provinceCode); f.cityCode = f.barangayCode = ''; cities.value = await get(`/provinces/${f.provinceCode}/cities-municipalities/`); brgys.value = [] }
async function onCity() { f.city = nm(cities.value, f.cityCode); f.barangayCode = ''; brgys.value = await get(`/cities-municipalities/${f.cityCode}/barangays/`) }
const onBrgy = () => (f.barangay = nm(brgys.value, f.barangayCode))
onMounted(async () => { const u = user.value; f.name = u.name || ''; f.phone = u.phone || ''; regions.value = await get('/regions/')
  if (u.region_code) { Object.assign(f, { regionCode: u.region_code, region: u.region, provinceCode: u.province_code, province: u.province, cityCode: u.city_code, city: u.city, barangayCode: u.barangay_code, barangay: u.barangay })
    provinces.value = await get(`/regions/${f.regionCode}/provinces/`); cities.value = f.provinceCode ? await get(`/provinces/${f.provinceCode}/cities-municipalities/`) : await get(`/regions/${f.regionCode}/cities-municipalities/`); if (f.cityCode) brgys.value = await get(`/cities-municipalities/${f.cityCode}/barangays/`) } })
async function save() {
  if (!/^[\p{L} .'\-]{2,80}$/u.test(f.name)) return notify('Enter a valid name', 'err')
  if (!/^(09|\+639)\d{9}$/.test(f.phone)) return notify('Phone must look like 09XXXXXXXXX', 'err')
  busy.value = true; try { user.value = await api('/me', { method: 'PUT', body: f }); notify('Profile saved') } finally { busy.value = false } }
async function deactivate() { if (!confirm('Deactivate your account?')) return; await api('/me', { method: 'DELETE' }); localStorage.removeItem('tk'); user.value = null; navigateTo('/') }
definePageMeta({ middleware: 'guard' })
</script>
<template>
  <div class="card pad" style="max-width:640px;margin:auto">
    <p class="kicker">Attendee card</p><h1 class="h2">My Profile</h1><hr class="rule2">
    <p class="muted">{{ user.email }} · role: {{ user.role }}</p>
    <label class="label">Full name</label><input v-model="f.name" class="input">
    <label class="label">Mobile number</label><input v-model="f.phone" class="input" placeholder="09123456789">
    <label class="label">Region</label><select v-model="f.regionCode" class="input" @change="onRegion"><option value="">Select…</option><option v-for="r in regions" :key="r.code" :value="r.code">{{ r.name }}</option></select>
    <template v-if="provinces.length"><label class="label">Province</label><select v-model="f.provinceCode" class="input" @change="onProv"><option value="">Select…</option><option v-for="r in provinces" :key="r.code" :value="r.code">{{ r.name }}</option></select></template>
    <label class="label">City / Municipality</label><select v-model="f.cityCode" class="input" @change="onCity"><option value="">Select…</option><option v-for="r in cities" :key="r.code" :value="r.code">{{ r.name }}</option></select>
    <label class="label">Barangay</label><select v-model="f.barangayCode" class="input" @change="onBrgy"><option value="">Select…</option><option v-for="r in brgys" :key="r.code" :value="r.code">{{ r.name }}</option></select>
    <div style="margin-top:20px;display:flex;gap:10px"><button class="btn solid" :disabled="busy" @click="save"><span v-if="busy" class="spin" /> Save profile</button><button class="btn" @click="deactivate">Deactivate account</button></div>
  </div>
</template>
