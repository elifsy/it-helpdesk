delete from it_attachment;
delete from it_notify;
delete from it_missing_report;
delete from it_ticket_record;
delete from it_ticket_event;
delete from it_ticket;
delete from it_asset_request;
delete from it_asset_history;
delete from it_asset;
delete from it_asset_category;
delete from it_kb_article;
delete from it_solution_code;
delete from sys_user;
delete from sys_dept;
delete from sys_audit_log;
delete from sys_job_run;
delete from sys_setting where setting_key in ('runtime','role-permissions','dingtalk-bootstrap-admin','incoming-notifications','badge-titles-enabled');
delete from sys_setting where setting_key like 'tutorial:%';
insert into sys_dept(id,name,parent_id) values(1,'技术中心',0),(2,'研发部',1);
insert into sys_user(id,name,dept_id,dept_name,role,status) values
 (1,'张伟',1,'技术中心','USER','ACTIVE'),
 (2,'赵敏',1,'技术中心','VIEWER','ACTIVE'),
 (3,'陈静',1,'技术中心','ADMIN','ACTIVE'),
 (4,'李强',1,'技术中心','IT','ACTIVE'),
 (5,'王敏',1,'技术中心','IT','ACTIVE'),
 (6,'普通用户乙',1,'技术中心','USER','ACTIVE');
insert into it_asset(no,name,category,status) values('ASSET-TEST-1','测试笔记本','终端','闲置'),('ASSET-TEST-2','替换设备','终端','闲置');
insert into it_asset_category(name,sort) values('终端',0),('打印复印',1),('终端设备',2),('服务器',3);
insert into it_ticket(no,title,description,cat1,cat2,creator_id,creator_name,status,assignee_id,assignee_name,due_at)
 values('IT-TEST-0001','测试工单','回归测试专用工单','终端','无法开机',1,'张伟','IN_PROGRESS',4,'李强',dateadd('hour',4,current_timestamp));
insert into it_asset_request(id,no,type,asset_no,applicant,reason,status) values
 (1,'AS-TEST-1','RECEIVE','ASSET-TEST-1','张伟','测试领用','PENDING'),
 (2,'AS-TEST-2','RECEIVE','ASSET-TEST-1','赵敏','测试重复领用','PENDING');
insert into it_solution_code(id,category,code,name) values(1,'终端','RESTART','重启恢复');
