import assert from 'node:assert/strict'
import test from 'node:test'
import { ticketNotificationEntry } from '../src/utils/ticket-links.ts'

test('DingTalk notification keeps its ticket destination after converting to a hash route', () => {
  const entry = ticketNotificationEntry('https://helpdesk.example/ams/?ticket=IT-20261121-0001&corpid=fixture#/')
  assert.equal(entry.href, 'https://helpdesk.example/ams/?corpid=fixture#/ticket/IT-20261121-0001')
  assert.equal(ticketNotificationEntry(entry.href), null)
})

test('notification entry does not replace existing routes or accept path syntax as a ticket number', () => {
  for (const url of [
    'https://helpdesk.example/?ticket=IT-TEST-0001#/ticket/IT-TEST-0002',
    'https://helpdesk.example/?ticket=IT%2F123',
    'https://helpdesk.example/?ticket=%2F%2Fevil.example',
    'https://helpdesk.example/?asset=AS-0001'
  ]) {
    assert.equal(ticketNotificationEntry(url), null)
  }
})
