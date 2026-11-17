-- ============================================================
-- 内部 IT 报修平台 · 演示种子数据
-- 与前端演示版口径一致：12 余张历史工单用于统计图
-- ============================================================
USE it_helpdesk;
SET NAMES utf8mb4;

-- 部门
INSERT INTO sys_dept (id, name, parent_id, sort) VALUES
 (1, '信息技术部', 0, 1),
 (2, '技术中心',   0, 2),
 (3, '生产制造部', 0, 3),
 (4, '质量管理部', 0, 4),
 (5, '财务部',     0, 5),
 (6, '销售公司',   0, 6);

-- 人员（钉钉 userid 为演示值，真实环境由通讯录同步）
INSERT INTO sys_user (id, ding_user_id, name, employee_no, dept_id, dept_name, mobile, role, skill, location, status) VALUES
 (1, 'ding_zhangwei', '张伟', 'E10231', 2, '技术中心',   '13800000001', 'USER',                 NULL,           'B座 3F', 'ACTIVE'),
 (2, 'ding_wangmin',  '王敏', 'E10008', 1, '信息技术部', '13800000002', 'IT',                     NULL,           'A座 1F 服务台', 'ACTIVE'),
 (3, 'ding_liqiang',  '李强', 'E10015', 1, '信息技术部', '13800000003', 'IT',                 '网络,终端',    'A座 1F', 'ACTIVE'),
 (4, 'ding_sunpeng',  '孙鹏', 'E10021', 1, '信息技术部', '13800000004', 'IT',                 '系统,账号',    'A座 1F', 'ACTIVE'),
 (5, 'ding_zhaomin',  '赵敏', 'E10442', 5, '财务部',     '13800000005', 'VIEWER',                 NULL,           'C座 2F', 'ACTIVE'),
 (6, 'ding_chenjing', '陈静', 'E10001', 1, '信息技术部', '13800000006', 'ADMIN', NULL,        'A座 1F', 'ACTIVE'),
 (7, 'ding_liuyang',  '刘洋', 'E10318', 3, '生产制造部', '13800000007', 'USER',     NULL,           '厂区 2号车间', 'ACTIVE'),
 (8, 'ding_wangfang','王芳', 'E10509', 4, '质量管理部', '13800000008', 'USER',                 NULL,           'B座 4F', 'ACTIVE');

-- IT 资产
INSERT INTO it_asset (no, name, category, sn, user_name, location, buy_date, warranty, warranty_end, status, borrower, borrow_due) VALUES
 ('IT-PC-2024-0137', '联想 ThinkPad T14s',      '终端设备', 'PF5T71QA', '张伟',  'B座 3F 工位', '2024-03-12', '厂商保修', '2027-03-12', '在用',   NULL, NULL),
 ('IT-PC-2023-0088', '戴尔 Latitude 5440',      '终端设备', 'DL5440A1', '赵敏',  'C座 2F 工位', '2023-06-20', '厂商保修', '2026-06-20', '在用',   NULL, NULL),
 ('IT-PC-2025-0021', '联想 ThinkPad T14s（备机）','终端设备', 'PF5T71QB', NULL,   'A座 1F 备件库', '2025-04-10', '厂商保修', '2028-04-10', '闲置',  NULL, NULL),
 ('IT-PC-2025-0022', '惠普 ProBook 450（备机）', '终端设备', 'HP450B2',  NULL,   'A座 1F 备件库', '2025-05-08', '厂商保修', '2028-05-08', '闲置',  NULL, NULL),
 ('IT-PRN-2020-0007','惠普 M428fdw 一体机',      '打印复印', 'PRN428C',  '刘洋',  '厂区 2号车间', '2020-09-01', '已过保',   '2023-09-01', '在用',   NULL, NULL),
 ('IT-PRN-2021-0012','佳能 iR2625 复印机',       '打印复印', 'CN2625D',  NULL,   'B座 3F 公共区', '2021-11-15', '自保',     '2026-11-15', '在用',   NULL, NULL),
 ('IT-NET-2022-0003','华为 S5735 接入交换机',    '网络设备', 'HW5735E',  NULL,   'A座 1F 弱电间', '2022-02-18', '厂商保修', '2027-02-18', '在用',   NULL, NULL),
 ('IT-NET-2022-0004','H3C WA6320 无线 AP',       '网络设备', 'H3C6320F', NULL,   'B座 3F 走廊',  '2022-03-05', '厂商保修', '2027-03-05', '在用',   NULL, NULL),
 ('IT-MTG-2023-0002','罗技 Rally Bar 会议终端',  '会议室',   'LGRB2023', '陈静',  'A座 201 会议室', '2023-08-22', '厂商保修', '2026-08-22', '在用',   NULL, NULL),
 ('IT-SRV-2021-0001','戴尔 R750 应用服务器',     '服务器',   'DLR750G',  NULL,   'A座 1F 机房',  '2021-12-01', '厂商保修', '2026-12-01', '在用',   NULL, NULL),
 ('IT-PC-2022-0055', '联想 ThinkPad E14（待报废）','终端设备', 'PFE14C3',  NULL,   'A座 1F 备件库', '2022-04-11', '已过保',   '2025-04-11', '待报废', NULL, NULL);

INSERT INTO it_asset_history (asset_no, action, detail, operator, created_at) VALUES
 ('IT-PC-2024-0137', '领用', '领用给 张伟（AS-20240312-0001）', '陈静', DATE_SUB(NOW(), INTERVAL 600 DAY)),
 ('IT-PC-2024-0137', '维修', '更换键盘，清理风扇',               '李强', DATE_SUB(NOW(), INTERVAL 120 DAY)),
 ('IT-PC-2023-0088', '领用', '领用给 赵敏',                     '陈静', DATE_SUB(NOW(), INTERVAL 500 DAY)),
 ('IT-PRN-2020-0007','维修', '更换定影组件',                     '孙鹏', DATE_SUB(NOW(), INTERVAL 90 DAY)),
 ('IT-NET-2022-0004','维修', '更换 POE 供电模块',                '李强', DATE_SUB(NOW(), INTERVAL 45 DAY));

-- 解决代码字典
INSERT INTO it_solution_code (category, code, name, sort) VALUES
 ('网络', '无线认证',   '无线认证/信号问题', 1),
 ('网络', '有线故障',   '网口/交换机故障',   2),
 ('网络', 'VPN',        'VPN 连接问题',      3),
 ('终端', '驱动重装',   '驱动异常重装',      1),
 ('终端', '硬件更换',   '硬件故障更换',      2),
 ('终端', '系统重装',   '系统重装/恢复',     3),
 ('系统', '账号权限',   '账号与权限调整',    1),
 ('系统', '软件安装',   '软件安装/授权',     2),
 ('系统', '数据修复',   '数据异常修复',      3),
 ('打印', '耗材更换',   '硒鼓/纸张/耗材',    1),
 ('打印', '驱动配置',   '打印机驱动与队列',  2),
 ('其他', '用户培训',   '操作指导/培训',     1),
 ('其他', '无法复现',   '现场无法复现',      2),
 ('其他', '转厂商',     '转第三方厂商处理',  3);

-- 知识库
INSERT INTO it_kb_article (title, category, summary, content, views, helpful, status) VALUES
 ('笔记本连不上公司 WiFi 的自查四步', '网络', '先看是不是全层都断，再排查账号认证', '一、确认是否只有你一台设备异常。二、忘记网络后重新连接 TZME-Office。三、检查是否弹出认证页面并完成 AD 账号登录。四、仍失败请提交报修并注明工位号。', 1286, 214, 'PUBLISHED'),
 ('打印机显示脱机怎么办', '打印', '90% 是队列卡住或 IP 变了', '一、打印机面板确认网络灯常亮。二、电脑端删除卡住的打印任务。三、重启 Print Spooler 服务。四、仍脱机请联系 IT 更新打印机 IP。', 968, 176, 'PUBLISHED'),
 ('VPN 连接失败的常见原因', '网络', '证书过期、密码变更、客户端版本过旧', '一、确认密码是否刚修改过，需重新输入。二、检查客户端版本。三、证书过期需重新申请。四、家庭网络限制 443 端口时改用备用地址。', 742, 131, 'PUBLISHED'),
 ('会议室投屏连不上的排查顺序', '会议室', '先确认信号源，再确认 HDMI 协商', '一、确认会议终端已开机并选择正确输入源。二、笔记本外接屏设置为复制模式。三、更换 HDMI 线测试。四、无线投屏需在同一网段。', 615, 98, 'PUBLISHED'),
 ('AD 账号密码过期如何自助修改', '系统', '在域内电脑按提示修改，或联系服务台', '一、登录时按提示修改密码。二、密码规则：12 位以上，含大小写与数字。三、修改后需重新登录 VPN 与邮箱。', 534, 87, 'PUBLISHED'),
 ('共享盘访问被拒绝的处理', '系统', '多为权限组变更或映射残留', '一、断开现有映射。二、重新输入域账号连接。三、仍无权限请提交申请，注明需要访问的目录与用途。', 412, 63, 'PUBLISHED'),
 ('电脑开机蓝屏的应急处理', '终端', '先记录错误码，再决定是否重装', '一、拍照记录蓝屏错误码。二、断电重启尝试进入安全模式。三、备份重要数据。四、提交报修并附错误码。', 389, 57, 'PUBLISHED'),
 ('新员工入职设备准备清单', '终端', '笔记本、显示器、账号、门禁、邮箱', '一、资产管理员按岗位配置备机。二、开通 AD、邮箱、ERP 账号。三、绑定门禁与工位。四、入职当天现场交付并签字。', 276, 44, 'PUBLISHED');

-- 历史工单（近 14 天，用于统计看板）
INSERT INTO it_ticket (no, title, description, type, cat1, cat2, impact, work_impact, priority, status, asset_no,
  creator_id, creator_name, dept_name, location, assignee_id, assignee_name, source, tried_kb,
  solution_code, solution_note, rating, rating_tags, paused_min, created_at, accepted_at, due_at, finished_at, closed_at) VALUES
 ('IT-20261025-0001','笔记本连不上公司 WiFi','整层同事都连不上，重启路由器无效','INCIDENT','网络','无线连不上','FLOOR','BLOCKED','P1','CLOSED',NULL,1,'张伟','技术中心','B座 3F',3,'李强','钉钉工作台',1,'无线认证','重启无线控制器后整层恢复，抽查 10 台终端正常',5,'响应快,专业',0,
   DATE_SUB(NOW(), INTERVAL 13 DAY), DATE_SUB(NOW(), INTERVAL 13 DAY)+INTERVAL 8 MINUTE, DATE_SUB(NOW(), INTERVAL 13 DAY)+INTERVAL 4 HOUR, DATE_SUB(NOW(), INTERVAL 13 DAY)+INTERVAL 95 MINUTE, DATE_SUB(NOW(), INTERVAL 13 DAY)+INTERVAL 110 MINUTE),
 ('IT-20261026-0002','财务打印机卡纸且提示脱机','M428fdw 面板闪红灯','INCIDENT','打印','卡纸','SELF','BLOCKED','P3','CLOSED','IT-PRN-2020-0007',5,'赵敏','财务部','C座 2F',4,'孙鹏','钉钉工作台',0,'耗材更换','清理卡纸并更换搓纸轮',4,'专业',0,
   DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_SUB(NOW(), INTERVAL 12 DAY)+INTERVAL 20 MINUTE, DATE_SUB(NOW(), INTERVAL 12 DAY)+INTERVAL 16 HOUR, DATE_SUB(NOW(), INTERVAL 12 DAY)+INTERVAL 3 HOUR, DATE_SUB(NOW(), INTERVAL 12 DAY)+INTERVAL 3 HOUR),
 ('IT-20261027-0003','需开通 ERP 采购模块权限','新岗位需要查看采购订单','SERVICE_REQUEST','系统','账号权限','SELF','SLOW','P3','CLOSED',NULL,7,'刘洋','生产制造部','厂区 2号车间',4,'孙鹏','钉钉工作台',0,'账号权限','已加入 ERP-PURCHASE 角色组',5,'处理及时',0,
   DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_SUB(NOW(), INTERVAL 11 DAY)+INTERVAL 32 MINUTE, DATE_SUB(NOW(), INTERVAL 11 DAY)+INTERVAL 16 HOUR, DATE_SUB(NOW(), INTERVAL 11 DAY)+INTERVAL 4 HOUR, DATE_SUB(NOW(), INTERVAL 11 DAY)+INTERVAL 4 HOUR),
 ('IT-20261028-0004','VPN 无法连接，提示认证失败','在家办公，昨天还能用','INCIDENT','网络','VPN','SELF','BLOCKED','P3','CLOSED',NULL,8,'王芳','质量管理部','B座 4F',3,'李强','钉钉工作台',1,'VPN','密码过期，指导重置后恢复',5,'耐心',0,
   DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY)+INTERVAL 12 MINUTE, DATE_SUB(NOW(), INTERVAL 10 DAY)+INTERVAL 16 HOUR, DATE_SUB(NOW(), INTERVAL 10 DAY)+INTERVAL 70 MINUTE, DATE_SUB(NOW(), INTERVAL 10 DAY)+INTERVAL 80 MINUTE),
 ('IT-20261029-0005','会议室投屏无信号','201 会议室，客户来访前需要','INCIDENT','会议室','投屏','DEPT','BLOCKED','P2','CLOSED','IT-MTG-2023-0002',6,'陈静','信息技术部','A座 201',3,'李强','钉钉工作台',0,'驱动配置','更换 HDMI 线并更新会议终端固件',5,'响应快',0,
   DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 9 DAY)+INTERVAL 6 MINUTE, DATE_SUB(NOW(), INTERVAL 9 DAY)+INTERVAL 8 HOUR, DATE_SUB(NOW(), INTERVAL 9 DAY)+INTERVAL 40 MINUTE, DATE_SUB(NOW(), INTERVAL 9 DAY)+INTERVAL 50 MINUTE),
 ('IT-20261030-0006','笔记本风扇异响且发烫','运行 CAD 时明显','INCIDENT','终端','硬件','SELF','SLOW','P4','CLOSED','IT-PC-2024-0137',1,'张伟','技术中心','B座 3F',3,'李强','钉钉工作台',0,'硬件更换','清理风道并更换风扇',4,'','0',
   DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 8 DAY)+INTERVAL 55 MINUTE, DATE_SUB(NOW(), INTERVAL 8 DAY)+INTERVAL 40 HOUR, DATE_SUB(NOW(), INTERVAL 7 DAY)+INTERVAL 2 HOUR, DATE_SUB(NOW(), INTERVAL 7 DAY)+INTERVAL 3 HOUR),
 ('IT-20261031-0007','共享盘财务目录无法访问','提示拒绝访问','INCIDENT','系统','共享盘','SELF','BLOCKED','P3','CLOSED',NULL,5,'赵敏','财务部','C座 2F',4,'孙鹏','钉钉工作台',1,'账号权限','加入 FIN-SHARE 权限组',5,'专业',0,
   DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 7 DAY)+INTERVAL 25 MINUTE, DATE_SUB(NOW(), INTERVAL 7 DAY)+INTERVAL 16 HOUR, DATE_SUB(NOW(), INTERVAL 7 DAY)+INTERVAL 2 HOUR, DATE_SUB(NOW(), INTERVAL 7 DAY)+INTERVAL 2 HOUR),
 ('IT-20261101-0008','车间无线信号弱','扫码枪频繁掉线','INCIDENT','网络','无线信号','DEPT','SLOW','P2','CLOSED',NULL,7,'刘洋','生产制造部','厂区 2号车间',3,'李强','扫码',0,'无线认证','加装一台 AP 并调整信道',4,'','0',
   DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY)+INTERVAL 18 MINUTE, DATE_SUB(NOW(), INTERVAL 6 DAY)+INTERVAL 8 HOUR, DATE_SUB(NOW(), INTERVAL 5 DAY)+INTERVAL 3 HOUR, DATE_SUB(NOW(), INTERVAL 5 DAY)+INTERVAL 4 HOUR),
 ('IT-20261102-0009','新员工笔记本无法加入域','提示找不到域控制器','INCIDENT','终端','系统','SELF','BLOCKED','P3','CLOSED','IT-PC-2025-0021',1,'张伟','技术中心','B座 3F',4,'孙鹏','代客报修',0,'系统重装','重装系统后重新加域',5,'','0',
   DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY)+INTERVAL 35 MINUTE, DATE_SUB(NOW(), INTERVAL 5 DAY)+INTERVAL 16 HOUR, DATE_SUB(NOW(), INTERVAL 4 DAY)+INTERVAL 1 HOUR, DATE_SUB(NOW(), INTERVAL 4 DAY)+INTERVAL 2 HOUR),
 ('IT-20261103-0010','邮箱收不到外部邮件','内部邮件正常','INCIDENT','系统','邮箱','SELF','SLOW','P3','CLOSED',NULL,8,'王芳','质量管理部','B座 4F',4,'孙鹏','钉钉工作台',1,'数据修复','清理黑名单并重建收件规则',4,'','0',
   DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 4 DAY)+INTERVAL 22 MINUTE, DATE_SUB(NOW(), INTERVAL 4 DAY)+INTERVAL 16 HOUR, DATE_SUB(NOW(), INTERVAL 4 DAY)+INTERVAL 5 HOUR, DATE_SUB(NOW(), INTERVAL 4 DAY)+INTERVAL 6 HOUR),
 ('IT-20261104-0011','需要安装 SolidWorks 2024','新项目需要，已有授权','SERVICE_REQUEST','系统','软件安装','SELF','SLOW','P3','CLOSED','IT-PC-2024-0137',1,'张伟','技术中心','B座 3F',3,'李强','钉钉工作台',0,'软件安装','安装并绑定许可服务器',5,'','0',
   DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)+INTERVAL 40 MINUTE, DATE_SUB(NOW(), INTERVAL 3 DAY)+INTERVAL 16 HOUR, DATE_SUB(NOW(), INTERVAL 2 DAY)+INTERVAL 2 HOUR, DATE_SUB(NOW(), INTERVAL 2 DAY)+INTERVAL 2 HOUR),
 ('IT-20261105-0012','复印机扫描件发不到邮箱','扫描后提示发送失败','INCIDENT','打印','扫描','DEPT','SLOW','P2','CLOSED','IT-PRN-2021-0012',5,'赵敏','财务部','B座 3F',4,'孙鹏','钉钉工作台',0,'驱动配置','更新 SMTP 配置与证书',4,'','0',
   DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)+INTERVAL 15 MINUTE, DATE_SUB(NOW(), INTERVAL 2 DAY)+INTERVAL 8 HOUR, DATE_SUB(NOW(), INTERVAL 1 DAY)+INTERVAL 4 HOUR, DATE_SUB(NOW(), INTERVAL 1 DAY)+INTERVAL 5 HOUR),
 ('IT-20261106-0013','办公区网络时断时续','多个工位反馈','INCIDENT','网络','有线故障','FLOOR','BLOCKED','P1','IN_PROGRESS',NULL,7,'刘洋','生产制造部','厂区 2号车间',3,'李强','钉钉工作台',0,NULL,NULL,NULL,NULL,0,
   DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)+INTERVAL 5 MINUTE, DATE_SUB(NOW(), INTERVAL 1 DAY)+INTERVAL 4 HOUR, NULL, NULL),
 ('IT-20261107-0014','笔记本电池鼓包','外壳已经变形','INCIDENT','终端','硬件','SELF','BLOCKED','P3','PENDING_CONFIRM','IT-PC-2024-0137',1,'张伟','技术中心','B座 3F',3,'李强','钉钉工作台',0,'硬件更换','更换电池与底壳，已交付使用',NULL,NULL,0,
   DATE_SUB(NOW(), INTERVAL 6 HOUR), DATE_SUB(NOW(), INTERVAL 6 HOUR)+INTERVAL 10 MINUTE, DATE_SUB(NOW(), INTERVAL 6 HOUR)+INTERVAL 16 HOUR, DATE_SUB(NOW(), INTERVAL 2 HOUR), NULL),
 ('IT-20261107-0015','新入职员工需要一台笔记本','下周一入职，技术岗','SERVICE_REQUEST','终端','领用','SELF','FINE','P4','NEW',NULL,6,'陈静','信息技术部','A座 1F',NULL,NULL,'代客报修',0,NULL,NULL,NULL,NULL,0,
   DATE_SUB(NOW(), INTERVAL 3 HOUR), NULL, DATE_SUB(NOW(), INTERVAL 3 HOUR)+INTERVAL 40 HOUR, NULL, NULL);

-- 工单事件（节选）
INSERT INTO it_ticket_event (ticket_no, from_status, to_status, action, operator, remark, created_at) VALUES
 ('IT-20261025-0001', NULL, 'NEW', 'CREATE', '张伟', '提交报修', DATE_SUB(NOW(), INTERVAL 13 DAY)),
 ('IT-20261025-0001', 'NEW', 'ACCEPTED', 'ACCEPT', '王敏', '已受理', DATE_SUB(NOW(), INTERVAL 13 DAY)+INTERVAL 8 MINUTE),
 ('IT-20261025-0001', 'ACCEPTED', 'IN_PROGRESS', 'DISPATCH', '王敏', '派单给 李强', DATE_SUB(NOW(), INTERVAL 13 DAY)+INTERVAL 12 MINUTE),
 ('IT-20261025-0001', 'IN_PROGRESS', 'PENDING_CONFIRM', 'FINISH', '李强', '重启无线控制器后恢复', DATE_SUB(NOW(), INTERVAL 13 DAY)+INTERVAL 95 MINUTE),
 ('IT-20261025-0001', 'PENDING_CONFIRM', 'CLOSED', 'RATE', '张伟', '评价 5 星', DATE_SUB(NOW(), INTERVAL 13 DAY)+INTERVAL 110 MINUTE),
 ('IT-20261107-0014', NULL, 'NEW', 'CREATE', '张伟', '提交报修', DATE_SUB(NOW(), INTERVAL 6 HOUR)),
 ('IT-20261107-0014', 'NEW', 'ACCEPTED', 'ACCEPT', '王敏', '已受理', DATE_SUB(NOW(), INTERVAL 6 HOUR)+INTERVAL 10 MINUTE),
 ('IT-20261107-0014', 'ACCEPTED', 'IN_PROGRESS', 'DISPATCH', '王敏', '派单给 李强', DATE_SUB(NOW(), INTERVAL 6 HOUR)+INTERVAL 12 MINUTE),
 ('IT-20261107-0014', 'IN_PROGRESS', 'PENDING_CONFIRM', 'FINISH', '李强', '更换电池与底壳', DATE_SUB(NOW(), INTERVAL 2 HOUR));

INSERT INTO it_ticket_record (ticket_no, kind, content, cost_min, operator, created_at) VALUES
 ('IT-20261025-0001','REMOTE','远程查看无线控制器日志，发现 2.4G 射频异常，重启后恢复。',25,'李强', DATE_SUB(NOW(), INTERVAL 13 DAY)+INTERVAL 40 MINUTE),
 ('IT-20261025-0001','ONSITE','到 3F 现场抽查 10 台终端，连接正常，测速达标。',20,'李强', DATE_SUB(NOW(), INTERVAL 13 DAY)+INTERVAL 90 MINUTE),
 ('IT-20261107-0014','ONSITE','检测电池鼓包，更换电池与底壳，系统校准后交付。',45,'李强', DATE_SUB(NOW(), INTERVAL 2 HOUR));

-- 资产申请
INSERT INTO it_asset_request (no, type, asset_no, applicant, dept, reason, expect_return, status, approver, handled_at, new_asset_no, remark, created_at) VALUES
 ('AS-20261102-0001','RECEIVE','IT-PC-2025-0021','张伟','技术中心','新项目需要一台备用笔记本，申请长期领用。',NULL,'COMPLETED','陈静', DATE_SUB(NOW(), INTERVAL 5 DAY),NULL,'已配发', DATE_SUB(NOW(), INTERVAL 5 DAY)),
 ('AS-20261106-0002','BORROW','IT-PRN-2020-0007','赵敏','财务部','部门临时盘点需要移动打印，借用 3 天。', DATE_ADD(CURDATE(), INTERVAL 3 DAY),'APPROVED','陈静', DATE_SUB(NOW(), INTERVAL 1 DAY),NULL,NULL, DATE_SUB(NOW(), INTERVAL 1 DAY)),
 ('AS-20261107-0003','REPLACE','IT-PC-2023-0088','赵敏','财务部','原机使用 3 年、半年维修 5 次，申请更换新机。',NULL,'PENDING',NULL,NULL,NULL,NULL, DATE_SUB(NOW(), INTERVAL 5 HOUR));

-- 站内通知
INSERT INTO it_notify (to_user, title, content, type, ref_no, is_read, created_at) VALUES
 ('张伟','工单待确认','IT-20261107-0014 已处理完成，请确认是否解决。','TICKET','IT-20261107-0014',0, DATE_SUB(NOW(), INTERVAL 2 HOUR)),
 ('王敏','有新工单待受理','IT-20261107-0015 新入职员工需要一台笔记本','TICKET','IT-20261107-0015',0, DATE_SUB(NOW(), INTERVAL 3 HOUR)),
 ('陈静','资产申请待审批','AS-20261107-0003 资产更换申请待审批','ASSET','AS-20261107-0003',0, DATE_SUB(NOW(), INTERVAL 5 HOUR)),
 ('李强','工单即将超时','IT-20261106-0013 办公区网络时断时续，已接近解决时限','SLA','IT-20261106-0013',0, DATE_SUB(NOW(), INTERVAL 1 HOUR));
