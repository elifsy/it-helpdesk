export interface AssetChoice {
  no: string
  category: string
  status: string
  userName?: string | null
  borrower?: string | null
}
const empty = (value?: string | null) => !value?.trim()
/** 与后端设备状态规则一致；接口提交时仍由后端再次校验。 */
export function assetActions(asset: AssetChoice, userName: string): string[] {
  const actions: string[] = []
  if (!userName) return actions
  if (asset.status !== '已报废') actions.push('REPAIR')
  if (['闲置', '待入库'].includes(asset.status) && empty(asset.userName) && empty(asset.borrower)) actions.push('RECEIVE', 'BORROW')
  if (asset.status === '在用' && empty(asset.userName) && empty(asset.borrower)) actions.push('BORROW')
  if (asset.status === '在用' && asset.userName === userName) {
    actions.push('REPLACE')
    // 本人领用的设备也可直接发起归还（退库）
    actions.push('RETURN')
  }
  if (asset.status === '借出中' && asset.borrower === userName) actions.push('RETURN')
  return actions
}
/**
 * 可申请设备：按设备状态规则过滤，并排除本人已提交待审批申请（PENDING）的设备。
 * 一个设备可以被多人同时申请，但同一个人对同一设备只能有一条待审批申请。
 */
export function eligibleAssets<T extends AssetChoice>(
  assets: T[],
  type: string,
  userName: string,
  category = '',
  pendingNos: Iterable<string> = []
): T[] {
  const pending = pendingNos instanceof Set ? pendingNos : new Set(pendingNos)
  return assets.filter(asset =>
    (!category || asset.category === category)
    && !pending.has(asset.no)
    && assetActions(asset, userName).includes(type))
}
