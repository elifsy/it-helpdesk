import { test } from 'node:test'
import assert from 'node:assert/strict'
import { notificationEntry } from '../src/utils/notification-links.ts'

function incoming(path, hash = '#/') {
  return 'https://helpdesk.example/ams/?corpid=fixture&notice=' + encodeURIComponent(path) + hash
}

test('notification links keep DingTalk login parameters and open the exact request or ticket', () => {
  for (const path of ['/admin/purchase?request=PR-01', '/admin/approvals?request=AS-02',
    '/assets?purchase=PR-01', '/assets?request=AS-02', '/assets?returnAsset=ZC-01', '/ticket/IT-03']) {
    assert.equal(notificationEntry(incoming(path))?.href, 'https://helpdesk.example/ams/?corpid=fixture#' + path)
  }
})

test('notification links reject other routes, extra parameters and untrusted destinations', () => {
  for (const path of ['//example.com', '/admin/config', '/assets?purchase=PR-01&foo=bar',
    '/ticket/..', '/ticket/IT-001%2Fextra', '/assets?asset=AS-02', '/assets?returnAsset=ZC-01&extra=1']) {
    assert.equal(notificationEntry(incoming(path)), null)
  }
  assert.equal(notificationEntry(incoming('/ticket/IT-03', '#/home')), null)
})
