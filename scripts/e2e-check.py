# -*- coding: utf-8 -*-
"""对本地后端做一次端到端业务验证（不依赖前端）。"""
import json
import urllib.request
import urllib.error

BASE = 'http://127.0.0.1:8081/api'
results = []


def call(method, path, token=None, body=None):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode('utf-8'))
        except Exception:
            return {'code': -1, 'message': 'HTTP %s' % e.code}


def login(name):
    r = call('POST', '/auth/login', body={'demoUser': name})
    if r.get('code') != 0:
        raise RuntimeError('登录失败 %s: %s' % (name, r))
    return r['data']['token']


def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('  PASS  ' if cond else '  FAIL  ') + name + ('   [' + detail + ']' if detail else ''))
    return cond


print('\n=== 1. 登录与身份 ===')
t_emp = login('张伟')
t_desk = login('王敏')
t_eng = login('李强')
t_admin = login('陈静')
me = call('GET', '/auth/me', t_emp)
check('四个演示账号可登录', all([t_emp, t_desk, t_eng, t_admin]))
check('员工身份返回角色', me.get('data', {}).get('name') == '张伟', str(me.get('data', {}).get('roles')))

print('\n=== 2. 创建工单：自动定级与 SLA ===')
r = call('POST', '/tickets', t_emp, {
    'title': '财务打印机脱机',
    'location': 'B座3楼财务办公室',
    'description': 'B座3F打印机面板显示脱机，重启无效，部门无法打印。',
    'type': 'INCIDENT',
    'cat1': '打印',
    'cat2': '脱机',
    'impact': 'DEPT',
    'workImpact': 'BLOCKED',
    'assetNo': 'IT-PRN-2020-0007',
    'source': '钉钉工作台',
})
t = r.get('data') or {}
no = t.get('no')
check('工单创建成功', r.get('code') == 0 and bool(no), str(no))
check('优先级 = P2（部门 × 完全阻塞）', t.get('priority') == 'P2', str(t.get('priority')))
check('自动生成解决时限', bool(t.get('dueAt')), str(t.get('dueAt')))
check('工单号格式 IT-yyyyMMdd-xxxx', bool(no) and no.startswith('IT-') and len(no) == 16, str(no))

print('\n=== 3. 信息安全类强制 P1 ===')
r2 = call('POST', '/tickets', t_emp, {
    'title': '收到疑似钓鱼邮件',
    'location': 'B座3楼财务办公室',
    'description': '收到冒充财务总监的邮件，要求转账并提供账号密码。',
    'type': 'INCIDENT',
    'cat1': '信息安全',
    'cat2': '钓鱼邮件',
    'impact': 'SELF',
    'workImpact': 'FINE',
})
check('信息安全强制 P1', (r2.get('data') or {}).get('priority') == 'P1',
      str((r2.get('data') or {}).get('priority')))

print('\n=== 4. 工程师受理即接单 ===')
r = call('POST', '/tickets/%s/accept' % no, t_eng, {'remark': '已接单，开始处理'})
check('受理后状态 IN_PROGRESS', (r.get('data') or {}).get('status') == 'IN_PROGRESS',
      str((r.get('data') or {}).get('status')))
engs = call('GET', '/meta/engineers', t_desk).get('data') or []
li = next((e for e in engs if e.get('name') == '李强'), None)
check('工程师列表可查', li is not None, '共 %d 人' % len(engs))
d = r.get('data') or {}
check('接单工程师为当前工程师', d.get('assigneeName') == '李强' and d.get('assigneeId') == (li or {}).get('id'), str(d.get('assigneeName')))
check('已记录接单时间', bool(d.get('assignedAt')), str(d.get('assignedAt')))

print('\n=== 5. 工程师处理与关单 ===')
r = call('POST', '/tickets/%s/records' % no, t_eng,
         {'kind': 'ONSITE', 'content': '更换搓纸轮并清理卡纸，测试 20 页正常。', 'costMin': 25})
check('处理记录写入', r.get('code') == 0, '')
bad = call('POST', '/tickets/%s/finish' % no, t_eng, {'code': '', 'note': ''})
check('缺解决代码被拦截', bad.get('code') != 0, str(bad.get('message')))
r = call('POST', '/tickets/%s/finish' % no, t_eng,
         {'code': '耗材更换', 'note': '更换搓纸轮并清理卡纸，测试 20 页正常。'})
d = r.get('data') or {}
check('关单后状态 PENDING_CONFIRM', d.get('status') == 'PENDING_CONFIRM', str(d.get('status')))
check('解决代码已记录', d.get('solutionCode') == '耗材更换', str(d.get('solutionCode')))

print('\n=== 6. 员工确认与评价 ===')
detail = call('GET', '/tickets/%s' % no, t_emp).get('data') or {}
check('详情含可用操作 CONFIRM', 'CONFIRM' in (detail.get('actions') or []), str(detail.get('actions')))
check('时间轴有流转记录', len(detail.get('events') or []) >= 4, '%d 条' % len(detail.get('events') or []))
r = call('POST', '/tickets/%s/confirm' % no, t_emp, {'solved': True, 'score': 5, 'tags': '响应快,专业'})
d = r.get('data') or {}
check('确认后状态 CLOSED', d.get('status') == 'CLOSED', str(d.get('status')))
check('评价星级已保存', d.get('rating') == 5, str(d.get('rating')))

print('\n=== 7. 状态机约束 ===')
again = call('POST', '/tickets/%s/accept' % no, t_desk, {})
check('已关闭工单不能重复受理', again.get('code') != 0, str(again.get('message')))
perm = call('POST', '/tickets/%s/accept' % no, t_emp, {})
check('普通员工无权受理', perm.get('code') != 0, str(perm.get('message')))

print('\n=== 8. 资产领用申请与审批 ===')
# 每次运行新建一台专用测试资产，避免复用固定编号导致第二次跑时状态已变「在用」
import time
test_no = 'IT-E2E-' + time.strftime('%Y%m%d%H%M%S')
mk = call('POST', '/assets', t_admin, {
    'no': test_no, 'name': '自动化回归测试机', 'category': '终端设备',
    'status': '闲置', 'location': 'A座 1F', 'warranty': '厂商保修',
})
check('创建回归专用资产', mk.get('code') == 0, test_no)
r = call('POST', '/asset-requests', t_emp, {
    'type': 'RECEIVE',
    'assetNo': test_no,
    'reason': '新项目组需要一台备用笔记本，申请长期领用。',
})
ar = r.get('data') or {}
check('领用申请提交成功', r.get('code') == 0 and ar.get('status') == 'PENDING',
      str(ar.get('no')) + ' / ' + str(ar.get('status')))
bad = call('POST', '/asset-requests', t_emp, {
    'type': 'RETURN', 'assetNo': test_no, 'reason': '测试非法状态',
})
check('不符合状态的操作被拒', bad.get('code') != 0, str(bad.get('message')))
r = call('POST', '/asset-requests/%s/approve' % ar.get('id'), t_admin, {})
check('审批通过', (r.get('data') or {}).get('status') == 'COMPLETED',
      str((r.get('data') or {}).get('status')))
a = call('GET', '/assets/%s' % test_no, t_admin).get('data') or {}
check('资产转为「在用」', (a.get('asset') or {}).get('status') == '在用',
      str((a.get('asset') or {}).get('status')))
check('资产绑定使用人', (a.get('asset') or {}).get('userName') == '张伟',
      str((a.get('asset') or {}).get('userName')))
check('写入资产履历', len(a.get('history') or []) >= 1, '%d 条' % len(a.get('history') or []))

print('\n=== 9. 二维码 ===')
q = call('GET', '/assets/IT-PC-2024-0137/qrcode', t_admin).get('data') or {}
check('二维码内容含资产编号', 'IT-PC-2024-0137' in (q.get('content') or ''), str(q.get('content')))

print('\n=== 10. 统计看板 ===')
ov = call('GET', '/stats/overview', t_desk).get('data') or {}
check('总量已更新（>=17）', int(ov.get('total') or 0) >= 17, 'total=%s' % ov.get('total'))
check('SLA 达成率有值', ov.get('slaRate') is not None, 'slaRate=%s' % ov.get('slaRate'))
trend = call('GET', '/stats/trend?days=14', t_desk).get('data') or []
cat = call('GET', '/stats/category?days=30', t_desk).get('data') or []
eng = call('GET', '/stats/engineers?days=30', t_desk).get('data') or []
check('趋势有数据点', len(trend) > 0, '%d 天' % len(trend))
check('分类分布有数据', len(cat) > 0, '%d 类' % len(cat))
check('工程师排名有数据', len(eng) > 0, '%d 人' % len(eng))
deny = call('GET', '/stats/overview', t_emp)
check('普通员工看不到看板', deny.get('code') != 0, str(deny.get('message')))

print('\n=== 11. 知识库推荐 ===')
kb = call('GET', '/kb/recommend?cat1=%E6%89%93%E5%8D%B0&desc=%E6%89%93%E5%8D%B0%E6%9C%BA%E8%84%B1%E6%9C%BA%E5%8D%A1%E7%BA%B8', t_emp).get('data') or []
check('按分类+描述能推荐到条目', len(kb) > 0, '命中 %d 篇' % len(kb))

print('\n=== 12. 通知 ===')
nt = call('GET', '/notifies', t_desk).get('data') or []
check('服务台收到站内通知', len(nt) > 0, '%d 条' % len(nt))

ok = sum(1 for _, c, _ in results if c)
total = len(results)
print('\n' + '=' * 56)
print('结果：%d / %d 通过' % (ok, total))
if ok != total:
    print('失败项：')
    for n, c, d in results:
        if not c:
            print('  - ' + n + '  ' + d)
print('=' * 56)
