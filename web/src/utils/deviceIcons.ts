/**
 * 设备图标：一套共用的线稿图标 key、自动匹配规则与配色 class。
 * AssetPicker（资产/采购目录选择）和「采购目录」的图标配置都从这里取，避免各写一套。
 */
export interface DeviceIconDef {
  key: string
  label: string
  /** 配色族，对应 styles/main.css 里的 .art-xxx */
  family: 'compute' | 'display' | 'peripheral' | 'infra' | 'network' | 'mobile' | 'other'
}

export const DEVICE_ICONS: DeviceIconDef[] = [
  { key: 'laptop', label: '笔记本', family: 'compute' },
  { key: 'desktop', label: '台式机', family: 'compute' },
  { key: 'monitor', label: '显示器', family: 'display' },
  { key: 'projector', label: '投影仪', family: 'display' },
  { key: 'keyboard', label: '键盘', family: 'peripheral' },
  { key: 'mouse', label: '鼠标', family: 'peripheral' },
  { key: 'headset', label: '耳机', family: 'peripheral' },
  { key: 'printer', label: '打印机', family: 'peripheral' },
  { key: 'server', label: '服务器', family: 'infra' },
  { key: 'network', label: '网络设备', family: 'network' },
  { key: 'phone', label: '手机', family: 'mobile' },
  { key: 'tablet', label: '平板', family: 'mobile' },
  { key: 'generic', label: '其他设备', family: 'other' }
]

export const ICON_KEYS = DEVICE_ICONS.map(i => i.key)

export function iconLabel(key?: string | null): string {
  return DEVICE_ICONS.find(i => i.key === key)?.label || '其他设备'
}

/**
 * 按「分类 + 名称」猜一个图标；name 参与匹配是为了让「终端设备 / 罗技键盘」也能拿到键盘图标。
 * 顺序由具体到笼统，命中即返回。
 */
export function autoIconKey(category?: string | null, name?: string | null): string {
  const categoryText = category || ''
  const text = `${categoryText} ${name || ''}`
  if (/键盘|keyboard/i.test(text)) return 'keyboard'
  if (/鼠标|mouse/i.test(text)) return 'mouse'
  if (/耳机|耳麦|headset/i.test(text)) return 'headset'
  if (/打印|复印|扫描|printer/i.test(text)) return 'printer'
  if (/投影|projector/i.test(text)) return 'projector'
  if (/显示|屏|monitor/i.test(text)) return 'monitor'
  if (/平板|ipad|tablet/i.test(text)) return 'tablet'
  if (/手机|电话|phone/i.test(text)) return 'phone'
  if (/服务|存储|server/i.test(text)) return 'server'
  if (/网络|路由|交换|switch|router/i.test(text)) return 'network'
  if (/台式|主机|desktop/i.test(text)) return 'desktop'
  if (/笔记本|便携|laptop|macbook|thinkpad/i.test(text)) return 'laptop'
  if (/终端/.test(categoryText)) return 'laptop'
  if (/显示|会议/.test(categoryText)) return 'monitor'
  if (/外设/.test(categoryText)) return 'keyboard'
  return 'generic'
}

/** 卡片插图底色 class；传 key 直接取，传空走 generic */
export function iconClass(key?: string | null): string {
  return 'art-' + (key && ICON_KEYS.includes(key) ? key : 'generic')
}
