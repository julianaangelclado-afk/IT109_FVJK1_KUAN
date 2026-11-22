export const useUser = () => useState<any>('user', () => null)
export const useToast = () => useState<{ msg: string; type: string } | null>('toast', () => null)
export function notify(msg: string, type = 'ok') { const t = useToast(); t.value = { msg, type }; setTimeout(() => (t.value = null), 3800) }
export async function api<T = any>(path: string, opts: any = {}): Promise<T> {
  const tk = localStorage.getItem('tk')
  try { return await $fetch<T>('/api' + path, { ...opts, headers: tk ? { Authorization: 'Bearer ' + tk } : {} }) }
  catch (e: any) { if (!opts.quiet) notify(e?.data?.error || 'Request failed - please try again', 'err'); throw e }
}
export const peso = (c: number) => (c === 0 ? 'Free' : '₱' + (c / 100).toLocaleString('en-PH', { minimumFractionDigits: 2 }))
export const fmt = (s: string) => new Date(s).toLocaleString('en-PH', { dateStyle: 'medium', timeStyle: 'short' })
