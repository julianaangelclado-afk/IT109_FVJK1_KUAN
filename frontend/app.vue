<script setup lang="ts">
const user = useUser(), toast = useToast()
onMounted(() => window.addEventListener('mousemove', (e) => { const s = document.documentElement.style; s.setProperty('--x', e.clientX + 'px'); s.setProperty('--y', e.clientY + 'px') }))
function out() { localStorage.removeItem('tk'); user.value = null; navigateTo('/') }
</script>
<template>
  <div class="glow" />
  <div class="wrap">
    <header class="nav">
      <NuxtLink to="/"><h3 class="logo">Campus Tickets <span class="kicker muted">· Admit One</span></h3></NuxtLink>
      <nav>
        <NuxtLink to="/">Events</NuxtLink>
        <template v-if="user">
          <NuxtLink to="/tickets">My Tickets</NuxtLink>
          <NuxtLink v-if="['organizer','admin'].includes(user.role)" to="/organizer">Organizer</NuxtLink>
          <NuxtLink v-if="['organizer','admin'].includes(user.role)" to="/organizer/scan">Door Scan</NuxtLink>
          <NuxtLink v-if="user.role==='admin'" to="/admin">Admin</NuxtLink>
          <NuxtLink to="/profile">Profile</NuxtLink>
          <a href="#" @click.prevent="out">Sign out</a>
        </template>
        <NuxtLink v-else to="/login">Sign in</NuxtLink>
      </nav>
    </header>
    <main style="padding:28px 0 70px"><NuxtPage /></main>
    <footer class="kicker muted" style="padding-bottom:30px">Caraga State University · IT 109 · Sandbox payments only</footer>
  </div>
  <div v-if="toast" :class="['toast', toast.type === 'err' && 'err']">{{ toast.msg }}</div>
</template>
