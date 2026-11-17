# 本地跑起来（Windows，不用 Docker）

## 最快方式：双击 `start.cmd`

首次运行会让你输入一次 MySQL 密码（存在本机 `.env`，不进仓库），之后双击即可。

脚本会自动完成：检查 JDK/Node → 检查或安装前端依赖 → 建库导数据（已存在则跳过）→
检查 8080/5173 端口 → 起后端 → 起前端 → 打开浏览器。

```bat
start.cmd             :: 正常启动
start.cmd --reset-db  :: 重新建表并导演示数据（会清空现有数据）
start.cmd --no-browser:: 不自动打开浏览器
stop.cmd              :: 按端口兜底停掉后端/前端
```

日志在 `logs/server.log` 与 `logs/web.log`。在启动窗口按 `Ctrl+C` 会一起停掉。

### 本地启用钉钉免登

请编辑 `it-helpdesk/.env`；`.env.example` 是模板，修改它不会直接改变启动配置。在 `.env` 中填写：

```dotenv
DINGTALK_ENABLED=true
DINGTALK_CORP_ID=你的企业CorpId
DINGTALK_APP_KEY=你的应用AppKey
DINGTALK_APP_SECRET=你的应用AppSecret
DINGTALK_AGENT_ID=你的应用AgentId
```

在原启动窗口按 `Ctrl+C`，再双击 `start.cmd`。使用本次启动打印的前端地址。启动日志会显示实际读取的 `.env` 路径和“登录模式：钉钉免登（演示登录已关闭）”，并校验后端 `/api/auth/config` 返回的模式与企业 ID。

启动脚本会从 `server/target/runtime` 与 `server/target/runtime-update` 中选用较新的运行包，并打印实际路径。需要在旧服务运行期间打包时，可在 `server` 目录执行 `mvn -DskipTests "-Dhelpdesk.runtime-directory=target/runtime-update" package`，避免覆盖正在运行的 jar；重启后会选用新包。

启动脚本将钉钉配置传给 Java 后端，保存数据库密码和本机端口时保留钉钉与自定义配置。启用免登但四项必填配置不齐全时会明确报错。

开启后从钉钉工作台的应用入口访问；普通浏览器会提示在钉钉中打开，旧演示登录需要重新认证。手动 `java -jar` 启动不会自动加载项目 `.env`，需自行设置环境变量，或使用 `start.cmd`。

排查登录模式时，查看本次后端地址的 `/api/auth/config`：`demoEnabled:false` 表示钉钉免登，`true` 表示演示。该接口只返回模式和 CorpId，不返回 AppSecret。

### 首次登录与初始管理员

旧版遇到“你的钉钉账号尚未同步到服务台”时会阻止登录。现在钉钉认证成功后会读取当前人员资料并自动建档，默认为**普通用户**，不必等全量通讯录同步。已存在的账号保留原角色，停用账号仍然禁止登录；同名账号不会自动合并或继承管理员权限。

应用需要能够[查询用户详情](https://open.dingtalk.com/document/development/query-user-details)，并将登录人员纳入通讯录授权范围。资料读取失败时，页面会提示检查成员信息读取权限和授权范围；此时不会写入空白账号。已有同步部门会立即关联，未同步部门暂留空，之后由通讯录同步补齐。

如果提示 `88/60011` 或 `qyapi_get_member`，表示缺少查询人员资料的接口权限：由钉钉管理员进入[钉钉开发者后台](https://open-dev.dingtalk.com/)，选择 `.env` 的 AppKey 对应应用，在“开发配置 → 权限管理”（部分版本名为“权限与范围”）中搜索 `qyapi_get_member`，申请并开通“成员信息读取权限”，确认已开通或审批通过。再确认通讯录授权范围包含登录人员，并按控制台提示发布更新版本，重新进入应用。如果修改权限后仍返回旧结果，重启本地项目再尝试。平台的操作路径和发布要求可参考[阿里云官方接入说明](https://help.aliyun.com/en/agentcore/agentcore-configure-dingtalk-account-integration)。

`88` 是外层鉴权错误，单看它不能判断是权限不足还是凭证问题；页面会优先显示钉钉返回的子错误码。权限需要在钉钉平台开通，修改项目 `.env` 或管理员角色不会授予钉钉接口权限。

早期数据库可能没有 `sys_dept.ding_dept_id`，会在首次建档时报“服务器处理失败”。新版后端在启动时检查并增量补齐这个可空字段及唯一索引，保留原有部门、人员和业务数据；已存在的部门关联也会保留，重复启动可安全执行同一项检查。旧库升级与首次免登建档已纳入回归测试。

第一次接入时，按以下方式设置初始管理员：

1. 重启更新后的服务，从钉钉工作台进入，首次会以普通用户登录。
2. 点击右上角头像，在“当前账号”中复制自己的**钉钉 UserId**。
3. 在项目根目录 `.env` 填入 `DINGTALK_BOOTSTRAP_ADMIN_USER_ID=刚复制的UserId`。这里填写真实 UserId，不能填写姓名、手机号、CorpId 或应用 AppKey。
4. 在原启动窗口按 `Ctrl+C` 后重新运行 `start.cmd`；再次进入应用，点击头像 → **重新登录**。
5. 该账号通过钉钉认证后会成为初始管理员，再到人员管理和角色权限配置页面设置其他人员的角色；可在管理功能中执行真实通讯录同步。

只有服务端配置指定且通过钉钉认证的账号会初始化为管理员。初始化结果保存在数据库中，同一数据库只执行一次；之后删除此环境变量不会撤销已设置的角色。管理员后来调整的角色也不会被登录自动改回管理员。此变量可以在初始化完成后留空。

本次登录回归使用内存数据库和模拟钉钉 HTTP 响应，覆盖首次建档、重复登录、同名隔离、资料权限不足、停用拦截、并发登录、管理员初始化和后续角色调整。

下面是手工方式，脚本出问题时可以照着手动做。

## 你机器上的现状

| 依赖 | 状态 |
|---|---|
| JDK 17 | 已装（`java -version` 可用） |
| Node 22 / npm 10 | 已装 |
| MySQL | **已装 8.4，服务在 3306 运行**（需要你安装时设的 root 密码） |
| Redis | 没装 → 用 `REDIS_ENABLED=false` 降级，功能不受影响 |
| Docker | 没装 → 本地跑不需要 |

Redis 关闭后会自动降级：单号改用时间戳、看板不缓存、超时扫描不加分布式锁。只影响多实例部署，单机无感。

## 1. 建库并导入数据

在项目根目录（`it-helpdesk/`）打开 Git Bash：

```bash
MYSQL="/c/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe"

"$MYSQL" -uroot -p --default-character-set=utf8mb4 < sql/schema.sql
"$MYSQL" -uroot -p --default-character-set=utf8mb4 < sql/seed.sql
```

两条命令都会提示输入 root 密码。执行完可以验证：

```bash
"$MYSQL" -uroot -p -e "use it_helpdesk; select count(*) tickets from it_ticket; select count(*) assets from it_asset;"
```

应看到 15 张工单、11 台资产。

## 2. 起后端

jar 已经打好，不需要 Maven：

```bash
java -jar server/target/runtime/it-helpdesk.jar \
  --spring.datasource.password=你的root密码 \
  --helpdesk.redis-enabled=false
```

看到 `Started HelpdeskApplication in x seconds` 即成功。后端在 <http://localhost:8080/api>。

另开终端验证接口：

```bash
curl "http://localhost:8080/api/meta/categories"
curl -H "X-Demo-User: 王敏" "http://localhost:8080/api/tickets?page=1&size=5"
```

## 3. 起前端

再开一个终端：

```bash
cd web
npm run dev
```

打开 <http://localhost:5173>。

## 4. 走一遍主流程

顶栏头像「演示身份」切换：

1. **张伟**（员工）→ 我要报修 → 选分类/影响范围 → 提交，看自动算出的优先级与时限
2. **李强**（IT）→ 工作台 → 受理（自动由本人接单并进入处理中）
3. **李强**（工程师）→ 添加处理记录 → 提交完成（必须选解决代码）
4. **张伟** → 确认「已解决」+ 星级 → 工单关闭
5. **陈静** → 申请审批 / 统计看板 / 资产台账二维码

## 常见问题

- **8080 / 5173 被占用**：`start.cmd` 会自动往后找空闲端口（8081-8083、5174-5176），
  启动完成时会打印实际地址，不用手动改配置。
- **手机扫码打不开**：vite 默认只监听 localhost。需要手机访问时用
  `cd web && npm run dev -- --host`，并把二维码基址改成内网 IP（`.env` 的 `QRCODE_BASE_URL`）。
- **Access denied**：密码不是 3306 那台实例的 root 密码。可在 MySQL Workbench 里新建用户，例如
  `CREATE USER 'helpdesk'@'localhost' IDENTIFIED BY 'helpdesk123'; GRANT ALL ON it_helpdesk.* TO 'helpdesk'@'localhost';`
  然后把用户名密码写进 `.env` 的 `MYSQL_USER` / `MYSQL_ROOT_PASSWORD`。
- **手动指定端口**：后端加 `--server.port=8081`；前端用
  `VITE_API_TARGET=http://127.0.0.1:8081 npm run dev`。
- **SQL 执行报错字符集**：确认命令带了 `--default-character-set=utf8mb4`。
- **重新来一遍**：再执行一次 `sql/schema.sql`（脚本会 DROP 重建 12 张表），再执行 seed。
- **二维码扫不到**：默认 `QRCODE_BASE_URL=http://localhost:5173`，手机访问需要改成内网 IP，例如
  `--helpdesk.qrcode.base-url=http://192.168.1.20:5173`。
- **想换端口/密码不想每次敲**：在项目根目录建 `.env` 参考 `.env.example`，或写进 IDEA 的运行配置。

## 用 IDEA 打开（可选）

- 后端：`File → Open` 选 `server/pom.xml`，运行 `HelpdeskApplication`，在 Run Configuration 的
  Environment variables 里加 `HELPDESK_REDIS_ENABLED=false` 与 `SPRING_DATASOURCE_PASSWORD=你的密码`。
  注意本机 `mvn` 命令不可用（缺 plexus launcher），但 IDEA 自带 Maven，导入不受影响。
- 前端：`File → Open` 选 `web/`，`npm run dev`。
