/**
 * 钉钉 JSAPI 适配层。
 * 演示登录由后端配置控制；启用钉钉免登时，普通浏览器提示从钉钉工作台进入。
 */
export function isDingTalk(): boolean {
  return /DingTalk/i.test(navigator.userAgent)
}

function dd(): any {
  return (window as any).dd
}

function ddReady(): Promise<any> {
  return new Promise((resolve, reject) => {
    const api = dd()
    if (!api) {
      reject(new Error('钉钉 JSAPI 未加载'))
      return
    }
    api.ready(() => resolve(api))
    api.error((err: any) => reject(new Error(err?.errorMessage || '钉钉 JSAPI 初始化失败')))
  })
}

/** 免登：拿到一次性 authCode，交给后端换 userid */
export async function requestAuthCode(corpId: string): Promise<string> {
  const api = await ddReady()
  return new Promise((resolve, reject) => {
    api.runtime.permission.requestAuthCode({
      corpId,
      onSuccess: (res: any) => resolve(res.code),
      onFail: (err: any) => reject(new Error(err?.errorMessage || '免登失败'))
    })
  })
}

/** 扫码：返回二维码文本 */
export async function scanQrCode(): Promise<string> {
  const api = await ddReady()
  return new Promise((resolve, reject) => {
    api.biz.util.scan({
      type: 'qrCode',
      onSuccess: (res: any) => resolve(res.text),
      onFail: (err: any) => reject(new Error(err?.errorMessage || '扫码取消'))
    })
  })
}

/** 从扫码结果里解析资产编号：支持完整 URL(#asset=) 或纯编号 */
export { parseAssetNo } from './asset-links'

export function setTitle(title: string) {
  const api = dd()
  if (api?.biz?.navigationBar?.setTitle) {
    api.biz.navigationBar.setTitle({ title })
  }
  document.title = title
}
