import { ref } from 'vue'
import type { WorkflowKey } from '../config/workflows'

export const tutorialPractice = ref(false)
export const tutorialWorkflow = ref<WorkflowKey>()
export const tutorialWriteMessage = '练习到此为止，操作没有提交。'

export function isPracticeRead(method = 'get') {
  return ['get', 'head', 'options'].includes(method.toLowerCase())
}
