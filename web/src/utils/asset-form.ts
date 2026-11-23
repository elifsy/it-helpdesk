export interface AssetFormValue {
  erpNo: string
  name: string
  model: string
  category: string
  sn: string
  location: string
  buyDate: string
  warranty: string
  warrantyEnd: string
  status: string
  remark: string
  /** 卡片图标 key；空 = 按分类与名称自动匹配 */
  icon: string
}
export const NEW_ASSET_STATUSES = ['待入库', '闲置', '在用', '维修中', '待报废', '已报废']
export const ASSET_WARRANTY_OPTIONS = ['厂商保修', '自保', '已过保']
export function emptyAssetForm(category = ''): AssetFormValue {
  return { erpNo: '', name: '', model: '', category, sn: '', location: '', buyDate: '', warranty: '', warrantyEnd: '', status: '待入库', remark: '', icon: '' }
}
