# trycloudflare 临时预览

本机通过 `cloudflared` 分享最新前端构建包，并将 `/api` 转发给正在运行的报修后端。保留真实钉钉免登和角色权限。

## 启动和查看地址

1. 使用 `start.cmd` 启动本地报修平台，确保 `.env` 中 `DINGTALK_ENABLED=true` 且钉钉配置齐全。
2. 如有前端变更，先在 `web` 目录执行 `npm run build`。
3. 双击项目根目录 `preview.cmd`。脚本自动寻找当前报修后端，在 8789–8799 中选择空闲的本机端口，再开启临时隧道。
4. 在窗口中复制 `https://….trycloudflare.com`，或打开 `logs/preview/preview-url.txt`。再次运行启动脚本会复用仍可用的预览。

预览在后台运行，关闭启动窗口后仍可访问。电脑、网络、本地后端和预览进程需要保持运行。临时域名在重新创建隧道后会变化，相关行为见 [Cloudflare Quick Tunnels 文档](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)。

需要指定后端端口时，可以在 PowerShell 中执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start-preview.ps1 -BackendPort 8081
```

## 钉钉登录

请在钉钉内打开预览链接；普通浏览器显示“请在钉钉工作台中打开此应用”。如将它作为工作台正式免登入口，请在钉钉开发者后台按应用要求配置此地址和相关域名。预览脚本不会修改钉钉应用或本地企业配置。

预览只发布 `web/dist` 的入口和打包资源；源码、`.env` 与日志不作为静态文件发布。匿名业务接口返回 401，演示身份头不会转发。如果本地后端切回演示模式，预览接口会停止转发。

## 停止和日志

- 双击 `stop-preview.cmd`，只停止本项目的预览网关及隧道。
- 本地报修服务和官网项目的预览继续运行。
- 预览进程状态、网关和隧道日志存放于 `logs/preview/`，该目录不提交到版本库。

`cloudflared.exe` 位于 `.tools/`，使用与官网预览相同的官方程序，并单独存放。也可以通过环境变量 `CLOUDFLARED_PATH` 指定已安装的官方程序。程序下载说明见 [Cloudflare 官方下载页](https://developers.cloudflare.com/tunnel/downloads/)。
