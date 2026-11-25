export interface AssetChoice {
  no: string
  category: string
  status: string
  userId?: number | null
  userName?: string | null
  borrowerId?: number | null
  borrower?: string | null
  /** 已被「已批准待领取」的申请占用时的申请人 */
  reservedBy?: string | null
}
const empty = (value?: string | null) => !value?.trim()
export function hasAssetHolder(asset: AssetChoice) {
  return asset.userId != null || asset.borrowerId != null || !empty(asset.userName) || !empty(asset.borrower)
}
/** 与后端设备状态规则一致；接口提交时仍由后端再次校验。 */
export function assetActions(asset: AssetChoice, userId?: number): string[] {
  const actions: string[] = []
  if (userId == null) return actions
  if (asset.status !== '已报废') actions.push('REPAIR')
  // 已被待领取的申请占用：不能再被别人（或本人重复）领用/借用
  const reserved = !!asset.reservedBy
  if (!reserved && ['闲置', '待入库'].includes(asset.status) && !hasAssetHolder(asset)) actions.push('RECEIVE', 'BORROW')
  if (!reserved && asset.status === '在用' && !hasAssetHolder(asset)) actions.push('BORROW')
  if (asset.status === '在用' && asset.userId === userId) {
    actions.push('REPLACE')
    // 本人领用的设备也可直接发起归还（退库）
    actions.push('RETURN')
  }
  if (asset.status === '借出中' && asset.borrowerId === userId) actions.push('RETURN')
  return actions
}
/**
 * 可申请设备：按设备状态规则过滤，并排除本人已提交待审批申请（PENDING）的设备。
 * 一个设备可以被多人同时申请，但同一个人对同一设备只能有一条待审批申请。
 */
export function eligibleAssets<T extends AssetChoice>(
  assets: T[],
  type: string,
  userId?: number,
  category = '',
  pendingNos: Iterable<string> = []
): T[] {
  const pending = pendingNos instanceof Set ? pendingNos : new Set(pendingNos)
  return assets.filter(asset =>
    (!category || asset.category === category)
    && !pending.has(asset.no)
    && assetActions(asset, userId).includes(type))
}
