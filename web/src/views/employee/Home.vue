<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { assetApi, assetCategoryApi, assetRequestApi, ticketApi, type AssetCategory } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useMissingReportStore } from '../../stores/missingReports'
import { eligibleAssets } from '../../utils/assets'
import { REQ_STATUS_CLASS, REQ_STATUS_NAME, REQ_TYPE, remainText, statusName } from '../../utils/dict'
import { isDingTalk, parseAssetNo, scanQrCode } from '../../utils/dingtalk'
import { toast } from '../../utils/toast'
import { ticketQueueText } from '../../utils/ticketQueue'
import { assetOperationPath } from '../../utils/asset-links'
import AssetRequestFlow from '../../components/AssetRequestFlow.vue'
import PurchaseFlow from '../../components/PurchaseFlow.vue'
import PendingReportBadges from '../../components/PendingReportBadges.vue'

const router = useRouter()
const auth = useAuthStore()
const reportState = useMissingReportStore()
const pendingReports = computed(() => reportState.summary?.pending || 0)
const tickets = ref<any[]>([])
const reqs = ref<any[]>([])
const myRequests = ref<any[]>([])
const assets = ref<any[]>([])
const categories = ref<AssetCategory[]>([])
const loading = ref(true)
const assetsLoading = ref(true)
const flow = ref<InstanceType<typeof AssetRequestFlow>>()
const purchaseFlow = ref<InstanceType<typeof PurchaseFlow>>()

const active = computed(() => tickets.value.filter((t) => !['CLOSED', 'REJECTED', 'CANCELLED'].includes(t.status)))
const pending = computed(() => tickets.value.filter((t) => t.status === 'PENDING_CONFIRM'))
const hero = computed(() => pending.value[0] || active.value[0])
const myId = computed(() => auth.user?.id)
/** 本人待审批申请涉及的设备，四个入口里不再可选 */
const pendingNos = computed(() => myRequests.value
  .filter((r) => r.status !== 'COMPLETED' && r.status !== 'REJECTED' && r.status !== 'CANCELLED')
  .map((r) => r.assetNo))

/** 与「资产服务」页同一套四个入口：标题取 REQ_TYPE，副标题显示可选设备数 */
const quick = computed(() => (['RECEIVE', 'BORROW', 'REPLACE', 'RETURN'] as const).map((type) => ({
  type,
  label: REQ_TYPE[type].name.replace('资产', ''),
  desc: REQ_TYPE[type].desc,
  count: eligibleAssets(assets.value, type, myId.value, '', pendingNos.value).length
})))

async function loadRequests() {
  if (!auth.can('request.view.own') && !auth.can('request.view.all')) return
  try {
    const list = await assetRequestApi.list({ mine: true })
    myRequests.value = list || []
    reqs.value = myRequests.value.slice(0, 2)
  } catch {
    /* 申请列表异常不影响首页 */
  }
}

async function loadAssets() {
  assetsLoading.value = true
  try {
    const [a, c] = await Promise.all([assetApi.list({}), assetCategoryApi.list()])
    assets.value = a || []
    categories.value = c || []
  } catch {
    /* 资产数据异常不阻塞首页其他内容 */
  } finally {
    assetsLoading.value = false
  }
}

/** 购入申请：与领用同一套选择弹窗；没有合适设备时走这条路 */
function openPurchase() {
  if (reportState.summary?.blocked) { void router.push('/missing-reports'); return }
  if (!auth.can('request.create')) return
  purchaseFlow.value?.open()
}

function openQuick(type: string) {
  if (reportState.summary?.blocked) {
    void router.push(type === 'RETURN' ? '/assets?tab=overdue' : '/missing-reports')
    return
  }
  if (!auth.can('request.create')) return
  flow.value?.open(type)
}

/** 提交成功后同时刷新设备可用数与「我的申请」，让刚申请的设备立即从可选列表消失 */
function onSubmitted() {
  void loadAssets()
  void loadRequests()
}

onMounted(async () => {
  void loadAssets()
  void loadRequests()
  try {
    const page = auth.can('ticket.view.own') || auth.can('ticket.view.all')
      ? await ticketApi.list({ mine: true, page: 1, size: 5 })
      : { list: [] }
    tickets.value = page.list || []
  } catch (e: any) {
    toast(e.message)
  } finally {
    loading.value = false
  }
})

async function doScan() {
  try {
    const text = isDingTalk() ? await scanQrCode() : window.prompt('扫描测试：输入设备编号或二维码地址')
    if (text === null) return
    const no = parseAssetNo(text)
    if (no) await router.push(assetOperationPath(no))
    else toast('二维码中没有有效的设备编号')
  } catch (e: any) {
    toast(e.message)
  }
}
</script>

<template>
  <div class="home">
    <div class="col-main">
      <div class="home-heading" :class="{ 'has-reminder': pendingReports }">
        <div class="greet">
          <!-- <div class="eyebrow">内部 IT 服务台 · {{ auth.user?.deptName }}</div> -->
          <div class="hi">你好，{{ auth.user?.name }}。</div>
          <div class="sub">你有 {{ active.length }} 张工单处理中</div>
        </div>

        <section v-if="pendingReports" class="report-reminder" aria-label="待处理事项">
          <div class="report-reminder-copy">
            <b>{{ pendingReports }} 项待处理</b>
            <PendingReportBadges :counts="reportState.summary?.pendingByType" />
            <p>{{ reportState.summary?.blocked ? '请先补交事项或归还逾期设备，处理后可恢复新申请。' : '待补交事项和逾期设备共用同一额度。' }}</p>
          </div>
          <router-link class="btn sm pri report-reminder-link" to="/missing-reports">查看待处理事项</router-link>
        </section>
      </div>

      <router-link v-if="hero" class="hero" :to="'/ticket/' + encodeURIComponent(hero.no)" :aria-label="'查看工单 ' + hero.no + ' 的进度'">
        <span class="tag">{{ statusName(hero.status) }}</span>
        <div class="t">{{ hero.title }}</div>
        <div class="d">
          <span v-if="hero.assigneeName">工程师 {{ hero.assigneeName }} · </span>
          <span v-if="hero.overdue" style="color: #ffd8d0">{{ remainText(hero.remainMinutes, hero.workTime) }}</span>
          <span v-else>{{ remainText(hero.remainMinutes, hero.workTime, hero.slaPausedAt) || hero.priority }}</span>
        </div>
        <div class="f"><span class="mono">{{ hero.no }}</span><b>查看进度 ›</b></div>
        <div v-if="ticketQueueText(hero)" class="hero-queue">{{ ticketQueueText(hero) }}</div>
      </router-link>
      <div v-else class="hero empty-hero">
        <div class="t">当前没有处理中的工单</div>
        <div class="d">设备有问题？点下面的「我要报修」</div>
      </div>

      <div class="entries">
        <div v-if="auth.can('ticket.create')" class="entry span-all" data-workflow-step="repair-entry" role="button" tabindex="0"
          @click="router.push('/new')" @keydown.enter.prevent="($event.currentTarget as HTMLElement).click()"
          @keydown.space.prevent="($event.currentTarget as HTMLElement).click()">
          <div class="ic">＋</div>
          <div class="lt">
            <div class="l">报修</div>
            <PendingReportBadges :counts="reportState.summary?.pendingByType" />
            <div class="ldesc">描述故障位置和现象，系统自动定级并开始计时</div>
          </div>
          <span class="arrow">›</span>
        </div>
        <div class="entry scan-entry span-all" @click="doScan">
          <div class="ic blue">▣</div>
          <div class="lt">
            <div class="l">扫码</div>
            <div class="ldesc">扫描资产二维码，直接对这台设备发起操作</div>
          </div>
          <span class="arrow">›</span>
        </div>
      </div>

      <div class="asset-grid">
        <div
          v-for="q in quick"
          :key="q.type"
          class="qcard"
          :class="{ dim: !q.count }"
          :aria-label="REQ_TYPE[q.type].name"
          @click="openQuick(q.type)"
        >
          <div class="qi">{{ REQ_TYPE[q.type].icon }}</div>
          <div class="sp">
            <div class="qt">{{ q.label }}</div>
            <span v-if="pendingReports" class="pill b-orange">{{ pendingReports }} 项待处理</span>
            <div class="qm" :title="q.count ? `可选 ${q.count} 台设备` : '暂无可选设备'">{{ q.count ? `可选 ${q.count} 台` : '暂无可选' }}</div>
          </div>
          <span style="color: var(--t4)">›</span>
        </div>
        <div v-if="auth.can('request.create')" class="qcard purchase-card" aria-label="购入申请" @click="openPurchase">
          <div class="qi">⊕</div>
          <div class="sp">
            <div class="qt">购入申请</div>
            <span v-if="pendingReports" class="pill b-orange">{{ pendingReports }} 项待处理</span>
            <div class="qm">没有合适的设备可领？申请采购一台新的</div>
          </div>
          <span style="color: var(--t4)">›</span>
        </div>
      </div>

      <AssetRequestFlow
        ref="flow"
        :assets="assets"
        :categories="categories"
        :loading="assetsLoading"
        :reload="loadAssets"
        :pending-nos="pendingNos"
        @submitted="onSubmitted"
      />

      <PurchaseFlow ref="purchaseFlow" @submitted="loadAssets" />

      <div class="sec-t">
        <h3>最近工单</h3>
        <a @click="router.push('/tickets')">全部 ›</a>
      </div>
      <div v-if="!tickets.length" class="empty">还没有提交过工单</div>
      <router-link v-for="t in tickets" :key="t.no" class="wo" :to="'/ticket/' + encodeURIComponent(t.no)" :aria-label="'查看工单 ' + t.no + ' 的详情'">
        <div class="row">
          <div class="sp">
            <div class="tt">{{ t.title }}</div>
            <div class="mm">{{ t.no }} · {{ t.createdAt }}</div>
          </div>
          <span class="pill" :class="t.status === 'PENDING_CONFIRM' ? 'b-orange' : 'b-gray'">
            {{ statusName(t.status) }}
          </span>
        </div>
        <div v-if="ticketQueueText(t)" class="ticket-queue">{{ ticketQueueText(t) }}</div>
      </router-link>
    </div>

    <aside class="col-side">
      <div v-if="reqs.length" class="card">
        <div class="row">
          <b style="font-size: 13.5px">我的申请</b>
          <span class="sp" />
          <a @click="router.push('/assets')">全部 ›</a>
        </div>
        <div v-for="r in reqs" :key="r.id" class="req">
          <div class="row">
            <span style="font-size: 13px">{{ REQ_TYPE[r.type]?.name }} · {{ r.assetName || r.assetNo }}</span>
            <span class="sp" />
            <span class="pill" :class="REQ_STATUS_CLASS[r.status]">{{ REQ_STATUS_NAME[r.status] }}</span>
          </div>
          <div class="small muted" style="margin-top: 2px">{{ r.no }} · {{ r.createdAt }}</div>
        </div>
      </div>

      <div v-if="auth.can('kb.view')" class="kb-entry" @click="router.push('/kb')">
        <span style="color: var(--brand); font-size: 15px">▤</span>
        <span class="sp">知识库：常见问题先自助</span>
        <span style="color: var(--t4)">›</span>
      </div>

    </aside>
  </div>
</template>

<style scoped>
.home-heading {
  display: grid;
  gap: 8px;
}
.greet,
.report-reminder-copy {
  min-width: 0;
  overflow-wrap: anywhere;
}
.report-reminder {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 14px;
  padding: 14px 16px;
  border: 1px solid #ffd5c7;
  border-radius: 14px;
  background: #fff6f2;
}
.report-reminder-copy {
  flex: 1 1 11rem;
}
.report-reminder p {
  margin: 5px 0 0;
  font-size: 13px;
  color: var(--t2);
}
.report-reminder .report-reminder-link {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  margin-left: auto;
  line-height: 1;
  text-align: center;
  white-space: nowrap;
}
.hero-queue { margin-top:12px; padding-top:10px; border-top:1px solid rgba(255,255,255,.25); font-size:12px; line-height:1.6; }
.ticket-queue { margin-top:8px; color:var(--brand); font-size:12px; line-height:1.6; }
.greet {
  padding: 4px 2px 12px;
}
.greet .hi {
  font-size: 19px;
  font-weight: 800;
}
.hero {
  background: linear-gradient(115deg, #1677ff, #4096ff);
  border-radius: 14px;
  padding: 16px 17px;
  color: #fff;
  cursor: pointer;
}
.hero.empty-hero {
  background: #fff;
  color: var(--t1);
  box-shadow: var(--shadow);
}
.hero .tag {
  display: inline-block;
  font-size: 10.5px;
  background: rgba(255, 255, 255, 0.22);
  padding: 2px 9px;
  border-radius: 20px;
}
.hero .t {
  font-size: 15px;
  font-weight: 700;
  margin-top: 8px;
}
.hero .d {
  font-size: 12px;
  opacity: 0.9;
  margin-top: 5px;
}
.hero .f {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  font-size: 12px;
}
.entries {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
  margin-top: 12px;
}
.asset-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
  margin-top: 9px;
}
/* 购入申请：整行横跨，视觉上作为四张卡的补充入口 */
.purchase-card {
  grid-column: 1 / -1;
  border: 1px dashed #cfd9e6;
  box-shadow: none;
  background: #fcfdff;
}
.purchase-card:hover {
  border-color: #94bff1;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.06);
}
.purchase-card .qi {
  background: #eef2f7;
  color: var(--t2);
}
/* 资产入口卡样式在全局 main.css（.qcard），与「资产服务」页共用 */
.entry {
  flex: 1;
  background: #fff;
  border-radius: var(--r);
  padding: 12px 6px;
  text-align: center;
  cursor: pointer;
  box-shadow: var(--shadow);
  transition: 0.15s;
}
.entry:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(29, 33, 41, 0.08);
}
.entry .ic {
  width: 34px;
  height: 34px;
  margin: 0 auto 6px;
  border-radius: 10px;
  background: var(--brand-l);
  color: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
}
.entry .l {
  font-size: 12.5px;
  color: var(--t2);
}
.entry .lt { min-width: 0; }
.entry .ldesc,
.entry .arrow { display: none; }
.req {
  padding: 9px 0;
  border-bottom: 1px solid var(--line2);
}
.req:last-child {
  border-bottom: none;
}
.kb-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border-radius: 9px;
  padding: 12px 14px;
  margin-top: 10px;
  font-size: 12.5px;
  color: var(--t2);
  cursor: pointer;
  box-shadow: var(--shadow);
}
.wo {
  display: block;
  color: var(--t1);
  text-decoration: none;
  background: #fff;
  border-radius: var(--r);
  padding: 12px 14px;
  margin-bottom: 8px;
  cursor: pointer;
  box-shadow: var(--shadow);
  transition: 0.15s;
}
.wo:hover {
  box-shadow: 0 6px 16px rgba(29, 33, 41, 0.08);
}
.wo .tt {
  font-size: 13.5px;
  font-weight: 600;
}
.wo .mm {
  font-size: 11.5px;
  color: var(--t3);
  margin-top: 3px;
}
/* ---------------- PC 两栏 ---------------- */
@media (min-width: 900px) {
  .entry.scan-entry { display: none; }
  .home {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 348px;
    gap: 18px;
    align-items: start;
  }
  .col-main {
    container: employee-home / inline-size;
  }
  @container employee-home (min-width: 610px) {
    .home-heading.has-reminder {
      grid-template-columns: minmax(0, 1fr) minmax(0, 24rem);
      column-gap: 24px;
      align-items: center;
    }
  }
  .greet {
    padding-bottom: 14px;
  }
  .greet .hi {
    font-size: 22px;
  }
  .hero {
    padding: 20px 22px;
  }
  .hero .t {
    font-size: 17px;
  }
  .entries {
    display: flex;
    flex-direction: column;
    gap: 12px;
    margin-top: 14px;
  }
  .asset-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px;
    margin-top: 12px;
  }
  .qcard {
    padding: 13px;
  }
  .entry.span-all {
    flex: 1;
    justify-content: flex-start;
    gap: 14px;
    padding: 18px 22px;
  }
  .entry.span-all .ic {
    width: 46px;
    height: 46px;
    border-radius: 14px;
    font-size: 20px;
  }
  .entry.span-all .lt {
    display: flex;
    flex-direction: column;
    gap: 2px;
    align-items: flex-start;
    min-width: 0;
  }
  .entry.span-all .l {
    font-size: 15px;
    font-weight: 600;
  }
  .entry.span-all .ldesc {
    display: block;
    font-size: 12.5px;
    color: var(--t3);
    line-height: 1.4;
  }
  .entry.span-all .arrow {
    display: block;
    margin-left: auto;
    color: var(--t4);
    font-size: 18px;
  }
  .entry {
    padding: 16px 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 9px;
  }
  .entry .ic {
    margin: 0;
  }
  .entry .l {
    font-size: 13px;
  }
  .wo {
    padding: 14px 16px;
  }
  .wo .tt {
    font-size: 14px;
  }
  .col-side {
    position: sticky;
    top: 84px;
  }
}

/* ===== A · Quiet ===== */
.greet .eyebrow {
  font-size: 11.5px; color: var(--t3); letter-spacing: 0.07em;
  text-transform: uppercase; margin-bottom: 10px;
}
.greet .hi {
  font-size: 40px; font-weight: 600; letter-spacing: -0.038em; line-height: 1.1;
}
.greet .sub { font-size: 15px; color: var(--t2); margin-top: 10px; }
.hero {
  border-radius: 26px; padding: 30px 34px; margin-top: 26px;
  display: block; text-decoration: none;
  background: linear-gradient(120deg, #0066cc 0%, #0071e3 44%, #3b9bff 100%);
  box-shadow: 0 16px 40px rgba(0, 102, 204, 0.18);
  position: relative; overflow: hidden;
}
.hero:after {
  pointer-events: none;
  content: ''; position: absolute; width: 320px; height: 320px; border-radius: 50%;
  right: -90px; top: -170px; background: rgba(255, 255, 255, 0.11);
}
.hero .tag, .hero .t, .hero .d, .hero .f { position: relative; z-index: 1; }
.hero .tag {
  font-size: 11.5px; font-weight: 600; padding: 5px 12px; border-radius: 980px;
  background: rgba(255, 255, 255, 0.2);
}
.hero .t { font-size: 25px; font-weight: 600; letter-spacing: -0.025em; margin-top: 18px; }
.hero .d { font-size: 13px; opacity: 0.88; margin-top: 7px; }
.hero .f { margin-top: 26px; font-size: 11.5px; }
.hero .f b {
  background: #fff; color: #0066cc; font-size: 12.5px; font-weight: 600;
  padding: 9px 20px; border-radius: 980px;
}
.hero.empty-hero .f { display: none; }
.entry { border-radius: 20px; padding: 20px 14px; box-shadow: 0 2px 14px rgba(0, 0, 0, 0.035); }
.entry:hover { transform: translateY(-3px); box-shadow: 0 10px 26px rgba(0, 0, 0, 0.07); }
.entry .ic { width: 44px; height: 44px; border-radius: 50%; font-size: 19px; margin-bottom: 11px; }
.entry .l { font-size: 13.5px; font-weight: 600; }
.hero .f .mono { color: rgba(255, 255, 255, 0.82); }
@media (max-width:899px) {
  .greet .hi { font-size:25px; line-height:1.4; }
  .greet .eyebrow { font-size:11px; }
  .hero { padding:20px; border-radius:20px; }
  .hero .t { font-size:20px; overflow-wrap:anywhere; }
  .hero .f { gap:10px; flex-wrap:wrap; margin-top:20px; }
  .hero .f b { padding:9px 14px; }
  .entry { min-width:0; padding:16px 4px 14px; border-radius:16px; }
  .asset-grid { gap:9px; margin-top:9px; }
  .qcard { padding:13px 14px; border-radius:18px; }
  .qi { width:36px; height:36px; border-radius:10px; }
  .qt { font-size:13px; }
  .qm { font-size:11px; }
  .entry .ic { width:40px; height:40px; margin-bottom:8px; }
  .entry .l { font-size:13px; }
  .wo .row > .sp { min-width:0; }
  .wo .tt { overflow-wrap:anywhere; }
}
</style>
