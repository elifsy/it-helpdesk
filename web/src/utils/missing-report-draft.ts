import type { MissingReportRequester, MissingReportType } from '../api'

export interface MissingReportDraft {
  registration: {
    title: string; description: string; channel: string; completedAt: string
    businessType?: MissingReportType; assetNo?: string; newAssetNo?: string; expectReturn?: string
  }
  requester?: MissingReportRequester
  evidence?: File[]
}

type StoredDraft = Omit<MissingReportDraft, 'evidence'> & { evidence?: File | File[] }

const storeName = 'registration'
let connection: Promise<IDBDatabase> | undefined

function openDatabase() {
  connection ??= new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open('it-helpdesk-missing-report-drafts', 1)
    request.onupgradeneeded = () => request.result.createObjectStore(storeName)
    request.onsuccess = () => {
      const database = request.result
      database.onversionchange = () => {
        database.close()
        connection = undefined
      }
      resolve(database)
    }
    request.onerror = () => reject(request.error)
  }).catch(error => {
    connection = undefined
    throw error
  })
  return connection
}

export async function readMissingReportDraft(account: string): Promise<MissingReportDraft | undefined> {
  const database = await openDatabase()
  const request = database.transaction(storeName).objectStore(storeName).get(account)
  return new Promise((resolve, reject) => {
    request.onsuccess = () => {
      const saved: StoredDraft | undefined = request.result
      const evidence = saved?.evidence
      resolve(saved && { ...saved, evidence: evidence && (Array.isArray(evidence) ? evidence : [evidence]) })
    }
    request.onerror = () => reject(request.error)
  })
}

export async function writeMissingReportDraft(account: string, draft?: MissingReportDraft) {
  const database = await openDatabase()
  const transaction = database.transaction(storeName, 'readwrite')
  const store = transaction.objectStore(storeName)
  if (draft) store.put(draft, account)
  else store.delete(account)
  await new Promise<void>((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onabort = () => reject(transaction.error)
  })
}
