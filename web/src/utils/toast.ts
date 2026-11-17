import { ref } from 'vue'

export const toastText = ref('')
let timer: number | undefined

export function toast(msg: string) {
  toastText.value = msg
  window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    toastText.value = ''
  }, 2200)
}
