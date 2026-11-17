# 正式服务器部署

地址：<https://ams.tzme.net>；服务器 `1.92.92.68`。HTTP 自动跳转 HTTPS，证书自动续期已启用。

## 运行位置

| 项目 | 配置 |
|---|---|
| 后端 tmux | `ams-backend`，Java 17，`127.0.0.1:18081` |
| 前端 tmux | `ams-frontend`，独立 Nginx，公网 80/443 |
| 当前版本 | `/opt/ams/current` → `/opt/ams/releases/20261116-asset-storage` |
| 启动脚本 | `/opt/ams/bin/backend.sh`、`frontend.sh`、`start.sh` |
| 后端配置 | `/etc/ams/backend.env`，仅 root 和服务账号可读 |
| 前端配置 | `/etc/ams/nginx.conf`，独立 PID `/opt/ams/run/nginx.pid` |
| 数据库 | `ams_helpdesk`，应用账号 `ams_app` 仅访问此库 |
| 附件目录 | `/opt/ams/data/uploads` |
| 日志目录 | `/opt/ams/logs` |

前后端实际运行在 tmux 中。`ams-tmux.service` 负责服务器重启后创建会话；`certbot.timer` 负责证书续期。
官网原有 `/etc/tzme/nginx.conf`、8080 后端和本机 8088 前端保持独立。

## 查看和重启

```bash
tmux ls
tmux attach -t ams-backend
tmux attach -t ams-frontend
```

依次按 `Ctrl+B`、`D` 退出查看，会话继续运行。

```bash
# 停止本项目的后端与前端，等待会话退出后重新启动
tmux send-keys -t ams-backend C-c
nginx -c /etc/ams/nginx.conf -s quit
/opt/ams/bin/start.sh

# 仅重载本项目 Nginx
nginx -t -c /etc/ams/nginx.conf
nginx -c /etc/ams/nginx.conf -s reload
```

`systemctl restart ams-tmux` 只补启缺失会话；应用更新需先停止对应进程。

## 数据与配置

已迁移 1,382 个人员账号、152 个部门和账号角色，角色权限沿用现有配置或默认值，并保留解决代码字典。
工单、资产台账和资产申请使用新库。导入文件不在前端公开目录中。

2026-11-14 已上线报修「故障位置」必填项（最多 64 字），提交内容展示在工单详情中。本次无需修改数据库结构，更新前已备份到 `/root/ams-backups/before-location-20261114-092700.sql`，更新前后人员、部门、权限和业务数据校验一致。

同日已上线关单知识沉淀，版本为 `20261114-knowledge`。为知识表增加来源工单、内容版本和来源唯一索引；原有业务数据及已有知识校验一致。更新前备份：`/root/ams-backups/before-knowledge-20261114-135749.sql`。流程及权限见 [KNOWLEDGE-CAPTURE.md](KNOWLEDGE-CAPTURE.md)。

2026-11-16 已上线完整资产新增表单及型号字段，版本为 `20261116-asset-fields`。新增与编辑共用资料字段，台账和设备选择支持按型号搜索；旧台账增加可空的 `model varchar(128)`。更新前备份：`/root/ams-backups/before-asset-fields-20261116-103914.sql`。已有资产资料、人员、权限、工单及知识数据校验一致。

同日更新至 `20261116-asset-storage`：新增、编辑时，填写非空白存放位置会将待入库状态自动改为闲置，前端即时显示、后端保存时执行，其他状态保持。未批量修改旧设备，已有位置的待入库设备下次保存时生效。更新前备份：`/root/ams-backups/before-asset-storage-20261116-105358.sql`；业务及完整资产资料校验一致。108项后端测试、6组资产表单浏览器检查及正式站点检查通过。

DNS 已设置：`tzme.net` 下主机记录 `ams`，类型 `A`，值 `1.92.92.68`，TTL 默认或 600 秒。
域名 HTTPS 及钉钉服务器访问令牌检查通过。正式环境禁止演示账号和匿名业务访问。

钉钉工作台中此应用的首页地址需要改为 `https://ams.tzme.net`；若后台设置了应用可信域名，同步加入 `ams.tzme.net`。

## 检查与备份

```bash
curl -I https://ams.tzme.net
curl -s https://ams.tzme.net/api/auth/config
systemctl status ams-tmux.service certbot.timer
tail -n 100 /opt/ams/logs/backend.log

# 在服务器 root 会话中执行，备份位于非公开目录
install -d -m 700 /root/ams-backups
mysqldump --single-transaction --routines --triggers ams_helpdesk > /root/ams-backups/database.sql
chmod 600 /root/ams-backups/database.sql
tar -czf /root/ams-backups/uploads.tar.gz -C /opt/ams/data uploads
chmod 600 /root/ams-backups/uploads.tar.gz
```

更新前备份数据库、附件和 `/etc/ams`，发布到新的版本目录后切换 `current`。
已有库不能执行 `sql/schema.sql` 的重建操作。已生效的 HTTPS 配置参考 `deploy/tmux/nginx-https.conf`，`nginx.conf` 是首次申请证书前的 HTTP 配置。

## 本地 Navicat 连接

已创建 `ams_navicat@localhost`，只授予 `ams_helpdesk` 库的查询、增删改及查看视图权限。

| 页签 | 字段 | 配置 |
|---|---|---|
| 常规 | 主机 / 端口 | `127.0.0.1` / `3306` |
| 常规 | 用户名 | `ams_navicat` |
| 常规 | 密码 | 本机私有文件 `logs/deploy-ams/navicat-connection.txt` |
| SSH | 使用 SSH 通道/隧道 | 勾选 |
| SSH | 主机 / 端口 | `1.92.92.68` / `22` |
| SSH | 用户名 / 验证方法 | `root` / 密码 |
| SSH | 密码 | 服务器 SSH 登录密码 |

点击「测试连接」，成功后保存并打开 `ams_helpdesk`。SSH 页启用后，常规页的 `127.0.0.1` 指服务器本机。
已从本机通过 SSH 转发验证专用账号可查询和增删改；MySQL 仍只监听服务器本机，不需要开放公网 3306。
