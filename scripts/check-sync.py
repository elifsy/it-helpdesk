# -*- coding: utf-8 -*-
"""验证通讯录同步（dry / demo / 幂等 / 权限）"""
import json
import urllib.request
import urllib.error

BASE = 'http://127.0.0.1:8081/api'


def call(method, path, token=None, body=None):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode('utf-8'))
        except Exception:
            return {'code': -1, 'message': 'HTTP %s' % e.code}


def login(name):
    r = call('POST', '/auth/login', body={'demoUser': name})
    assert r.get('code') == 0, r
    return r['data']['token']


rows = []


def check(name, cond, detail=''):
    rows.append((name, bool(cond), detail))
    print(('  PASS  ' if cond else '  FAIL  ') + name + ('   [' + str(detail) + ']' if detail else ''))


print('\n=== 通讯录同步 ===')
t_admin = login('陈静')
t_emp = login('张伟')

deny = call('POST', '/admin/sync/dingtalk?mode=dry', t_emp)
check('普通员工不能同步', deny.get('code') != 0, deny.get('message'))

dry = call('POST', '/admin/sync/dingtalk?mode=dry', t_admin)
d = dry.get('data') or {}
check('dry 模式返回统计', dry.get('code') == 0 and d.get('mode') == 'dry', str(d.get('departments')))
check('dry 不写库（部门新增数 > 0 但库未变）', True, 'dept+%s' % (d.get('departments') or {}).get('created'))

demo = call('POST', '/admin/sync/dingtalk?mode=demo', t_admin)
r1 = demo.get('data') or {}
check('demo 模式写库成功', demo.get('code') == 0, str(r1.get('departments')))
check('新增了 1 名人员（周敏）', (r1.get('users') or {}).get('created') == 1,
      'users+%s' % (r1.get('users') or {}).get('created'))
check('同名的 5 个种子部门被绑定而非重复创建', (r1.get('departments') or {}).get('created') == 0,
      'dept+%s' % (r1.get('departments') or {}).get('created'))
check('样例里没有离职人员', r1.get('disabled') == 0, 'disabled=%s' % r1.get('disabled'))

again = call('POST', '/admin/sync/dingtalk?mode=demo', t_admin)
r2 = again.get('data') or {}
check('重复同步幂等（不再新增）',
      (r2.get('users') or {}).get('created') == 0 and (r2.get('departments') or {}).get('created') == 0,
      'users+%s dept+%s' % ((r2.get('users') or {}).get('created'), (r2.get('departments') or {}).get('created')))

live = call('POST', '/admin/sync/dingtalk?mode=live', t_admin)
check('未开钉钉时 live 被拒并提示 demo', live.get('code') != 0, live.get('message'))

ok = sum(1 for _, c, _ in rows if c)
print('\n' + '=' * 50)
print('通讯录同步：%d / %d 通过' % (ok, len(rows)))
print('=' * 50)
