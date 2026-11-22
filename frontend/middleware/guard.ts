export default defineNuxtRouteMiddleware((to) => {
  const u = useUser(); const roles = to.meta.roles as string[] | undefined
  if (!u.value) return navigateTo('/login?next=' + encodeURIComponent(to.fullPath))
  if (roles && !roles.includes(u.value.role)) return navigateTo('/')
})
