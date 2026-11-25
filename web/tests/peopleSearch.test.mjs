import { test } from 'node:test'
import assert from 'node:assert/strict'
import { matchingPeople, personDetails } from '../src/utils/people-search.ts'

const people = [
  { id: 1, name: '王小明', deptName: '技术中心', employeeNo: 'IT-01', skill: '终端' },
  { id: 2, name: '小明', department: '行政部', employeeNo: 'ADM-02' },
  { id: 3, name: '王小明', department: '生产部', employeeNo: 'OPS-03' }
]

test('people suggestions rank exact names and preserve distinct accounts with the same name', () => {
  assert.deepEqual(matchingPeople(people, '小明').map(person => person.id), [2, 1, 3])
  assert.deepEqual(matchingPeople(people, '王小明').map(person => person.id), [1, 3])
  assert.equal(personDetails(people[0]), '技术中心 · IT-01 · 终端')
})

test('people suggestions find partial text across name, department and employee number', () => {
  assert.deepEqual(matchingPeople(people, '  明 技术 ｉｔ-0 ').map(person => person.id), [1])
  assert.deepEqual(matchingPeople(people, '王 OPS').map(person => person.id), [3])
  assert.deepEqual(matchingPeople(people, '其他'), [])
})

test('people suggestions recognize pinyin, initials and alternate readings with other fields', () => {
  const directory = [
    { id: 1, name: '王小明', department: '技术中心', employeeNo: 'IT-01', namePinyin: [['wang'], ['xiao'], ['ming']] },
    { id: 2, name: '王小明', department: '生产部', namePinyin: [['wang'], ['xiao'], ['ming']] },
    { id: 3, name: '单吕', namePinyin: [['dan', 'shan'], ['lu']] }
  ]
  for (const query of ['wangxiaoming', 'WXM', 'wáng xiǎo míng', 'xiaom', 'xm']) {
    assert.deepEqual(matchingPeople(directory, query).map(person => person.id), [1, 2], query)
  }
  assert.deepEqual(matchingPeople(directory, 'ＷＸＭ 技术 IT-01').map(person => person.id), [1])
  for (const query of ['shānlǚ', 'SHANLV', 'sl', 'dl']) {
    assert.deepEqual(matchingPeople(directory, query).map(person => person.id), [3], query)
  }
  assert.deepEqual(matchingPeople(directory, 'lshan'), [])
})
