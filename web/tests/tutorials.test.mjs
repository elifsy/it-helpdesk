import { test } from 'node:test'
import assert from 'node:assert/strict'
import { tutorialsFor } from '../src/config/tutorials.ts'
import { workflowsFor } from '../src/config/workflows.ts'

const personal = ['ticket.create', 'ticket.view.own', 'request.create', 'request.view.own', 'asset.view', 'kb.view']
const staff = [...personal, 'stats.view', 'ticket.view.all', 'ticket.accept', 'ticket.handle', 'ticket.dispatch', 'asset.create',
  'request.view.all', 'request.approve', 'kb.manage', 'role.manage', 'user.manage', 'settings.manage']

test('each role gets its own tutorial content and super admins get no tutorials', () => {
  const employee = tutorialsFor(['USER'], personal)
  const engineer = tutorialsFor(['IT'], staff)
  const administrator = tutorialsFor(['ADMIN'], staff)
  const viewer = tutorialsFor(['VIEWER'], staff).find(course => course.key === 'tickets')
  assert.ok(employee.some(course => course.key === 'repair'))
  assert.ok(employee.every(course => course.group === '我的服务'))
  assert.equal(engineer.find(course => course.key === 'workbench').steps[0].title, '先处理待受理工单')
  assert.equal(administrator.find(course => course.key === 'workbench').steps[0].title, '查看团队当天积压')
  assert.ok(administrator.some(course => course.key === 'permissions'))
  assert.ok(!engineer.some(course => course.key === 'permissions'))
  assert.deepEqual(viewer.steps.map(step => step.title), ['查找团队工单', '查看处理轨迹'])
  assert.deepEqual(tutorialsFor(['SUPER_ADMIN', 'ADMIN'], staff), [])
})

test('custom permissions hide unavailable courses and individual operation steps', () => {
  const restricted = tutorialsFor(['IT'], ['ticket.view.all', 'asset.view', 'request.view.all'])
  assert.ok(!restricted.some(course => course.key === 'workbench' || course.key === 'repair'))
  for (const key of ['tickets', 'approvals']) assert.equal(restricted.find(course => course.key === key).steps.length, 1)
  assert.equal(restricted.find(course => course.key === 'ledger').steps.length, 2)
})
test('business practice follows live permissions and all flows end at a simulated submission', () => {
  for (const role of ['USER', 'IT', 'VIEWER', 'ADMIN']) {
    const workflows = workflowsFor([role], personal)
    assert.deepEqual(workflows.map(course => course.key), ['repair', 'RECEIVE', 'BORROW', 'RETURN', 'REPLACE'])
    assert.ok(workflows.every(course => course.steps.at(-1).mode === 'finish'))
  }
  assert.deepEqual(workflowsFor(['VIEWER'], ['ticket.create', 'asset.view']).map(course => course.key), ['repair'])
  assert.deepEqual(workflowsFor(['USER'], ['request.create']), [])
  assert.deepEqual(workflowsFor(['SUPER_ADMIN'], staff), [])
})
