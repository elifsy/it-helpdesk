import { defineStore } from 'pinia'
import { ref } from 'vue'
import { missingReportApi, type MissingReportSummary } from '../api'

export const useMissingReportStore = defineStore('missing-reports', () => {
  const summary = ref<MissingReportSummary>()
  const loadedFor = ref<number>()
  let request = 0

  async function refresh(userId: number | undefined) {
    const version = ++request
    if (loadedFor.value !== userId) summary.value = undefined
    try {
      const result = userId === undefined ? undefined : await missingReportApi.summary()
      if (version === request) summary.value = result
    } catch {
      // 保留同一账号最近一次状态。提交接口独立校验限制。
    } finally {
      if (version === request) loadedFor.value = userId
    }
  }

  return { summary, loadedFor, refresh }
})
