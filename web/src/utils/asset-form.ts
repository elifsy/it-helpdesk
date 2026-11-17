export interface AssetFormValue {
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
}
export const NEW_ASSET_STATUSES = ['待入库', '闲置', '在用', '维修中', '待报废', '已报废']
export function emptyAssetForm(category = ''): AssetFormValue & { no: string } {
  return { no: '', name: '', model: '', category, sn: '', location: '', buyDate: '', warranty: '', warrantyEnd: '', status: '待入库', remark: '' }
}
