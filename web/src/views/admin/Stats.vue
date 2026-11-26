<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { statsApi } from '../../api'
import { toast } from '../../utils/toast'
import StatsAssetDialog from '../../components/StatsAssetDialog.vue'

const ov = ref<any>({})
const trendRef = ref<HTMLElement>()
const catRef = ref<HTMLElement>()
const engineers = ref<any[]>([])
const problems = ref<any[]>([])
const selection = ref<{ kind: 'problem' | 'assets'; label: string; assetNo?: string; status?: string; key?: string } | null>(null)
const assetMetrics = computed(() => [
  { key: 'assetTotal', label: '资产总数', status: '' },
  { key: 'assetInUse', label: '在用', status: '在用' },
  { key: 'assetIdle', label: '闲置', status: '闲置' },
  { key: 'assetBorrowed', label: '借出中', status: '借出中' }
])
function updateCount(value: number) {
  if (selection.value?.kind === 'assets' && selection.value.key) ov.value[selection.value.key] = value
  else if (selection.value?.kind === 'problem') {
    const problem = problems.value.find(item => item.no === selection.value?.assetNo)
    if (problem) problem.times = value
  }
}
let chart1: echarts.ECharts | null = null
let chart2: echarts.ECharts | null = null

function fillTrend(list: any[], days: number) {
  const map = new Map(list.map((x: any) => [x.date, Number(x.count)]))
  const arr: any[] = []
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(Date.now() - i * 86400000)
    const key = `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    arr.push({ date: key, count: map.get(key) || 0 })
  }
  return arr
}

function resize() {
  chart1?.resize()
  chart2?.resize()
}

onMounted(async () => {
  try {
    const [o, tr, cat, eng, prob] = await Promise.all([
      statsApi.overview(),
      statsApi.trend(14),
      statsApi.category(30),
      statsApi.engineers(30),
      statsApi.problemAssets(5)
    ])
    ov.value = o
    engineers.value = eng || []
    problems.value = prob || []

    await nextTick()
    const filled = fillTrend(tr || [], 14)

    if (trendRef.value) {
      chart1 = echarts.init(trendRef.value)
      chart1.setOption({
        grid: { left: 38, right: 16, top: 18, bottom: 26 },
        tooltip: { trigger: 'axis' },
        xAxis: {
          type: 'category',
          data: filled.map((x) => x.date),
          axisLine: { lineStyle: { color: '#e5e6eb' } },
          axisTick: { show: false },
          axisLabel: { color: '#86909c', fontSize: 10 }
        },
        yAxis: {
          type: 'value',
          splitLine: { lineStyle: { color: '#f2f3f5' } },
          axisLabel: { color: '#86909c', fontSize: 10 }
        },
        series: [
          {
            type: 'line',
            smooth: true,
            symbolSize: 5,
            data: filled.map((x) => x.count),
            lineStyle: { color: '#1677ff', width: 2 },
            itemStyle: { color: '#1677ff' },
            areaStyle: { color: 'rgba(22,119,255,.12)' }
          }
        ]
      })
    }

    if (catRef.value) {
      const names = (cat || []).map((x: any) => x.name)
      const values = (cat || []).map((x: any) => Number(x.value))
      chart2 = echarts.init(catRef.value)
      chart2.setOption({
        grid: { left: 66, right: 28, top: 10, bottom: 16 },
        tooltip: { trigger: 'axis' },
        xAxis: {
          type: 'value',
          splitLine: { lineStyle: { color: '#f2f3f5' } },
          axisLabel: { color: '#86909c', fontSize: 10 }
        },
        yAxis: {
          type: 'category',
          data: names.slice().reverse(),
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: '#4e5969', fontSize: 11 }
        },
        series: [
          {
            type: 'bar',
            data: values.slice().reverse(),
            barWidth: 13,
            itemStyle: { color: '#1677ff', borderRadius: [0, 6, 6, 0] }
          }
        ]
      })
    }

    window.addEventListener('resize', resize)
  } catch (e: any) {
    toast(e.message)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart1?.dispose()
  chart2?.dispose()
})
</script>

<template>
  <div>
    <div class="page-h"><h2>统计看板</h2><span class="small muted">近 30 天</span></div>

    <div class="kpis timing-kpis">
      <div class="kpi"><div class="n">{{ ov.total || 0 }}</div><div class="l">工单总量</div></div>
      <div class="kpi"><div class="n" style="color: var(--brand)">{{ ov.slaRate || 0 }}%</div><div class="l">解决达成率</div></div>
      <div class="kpi"><div class="n">{{ ov.responseCount ? ov.avgResponseMinutes + ' 分' : '—' }}</div><div class="l">平均首次响应</div></div>
      <div class="kpi"><div class="n" style="color: var(--green)">{{ ov.avgHours || 0 }}h</div><div class="l">平均解决时长</div></div>
      <div class="kpi"><div class="n" style="color: var(--orange)">{{ ov.avgRating || '—' }}</div><div class="l">满意度（{{ ov.ratedCount || 0 }} 条）</div></div>
    </div>

    <div class="grid2">
      <div class="panel">
        <div class="ph"><b>近 14 天工单趋势</b></div>
        <div ref="trendRef" class="chart" />
      </div>
      <div class="panel">
        <div class="ph"><b>故障类型分布（近 30 天）</b></div>
        <div ref="catRef" class="chart" />
      </div>
    </div>

    <div class="grid2" style="margin-top: 12px">
      <div class="panel">
        <div class="ph"><b>工程师工作量</b></div>
        <table class="tb">
          <thead>
            <tr><th>工程师</th><th>处理量</th><th>满意度</th><th>平均首次响应</th><th>平均解决</th></tr>
          </thead>
          <tbody>
            <tr v-if="!engineers.length"><td colspan="5" class="empty">暂无数据</td></tr>
            <tr v-for="e in engineers" :key="e.name">
              <td style="font-weight: 600">{{ e.name }}</td>
              <td>{{ e.total }}</td>
              <td>{{ e.rating || '—' }}</td>
              <td>{{ e.responseCount ? e.avgResponseMinutes + ' 分' : '—' }}</td>
              <td>{{ e.resolvedCount ? e.avgMinutes + ' 分' : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="panel">
        <div class="ph"><b>问题设备 Top 5</b></div>
        <div v-if="!problems.length" class="empty">暂无数据</div>
        <button type="button" v-for="(p, i) in problems" :key="p.no" class="prob" :data-asset="p.no" aria-haspopup="dialog" :aria-label="'查看设备 ' + p.no + ' 的报修明细'" @click="selection = { kind: 'problem', label: '设备报修', assetNo: p.no }">
          <span class="rk">{{ i + 1 }}</span>
          <span class="mono">{{ p.no }}</span>
          <span class="sp" />
          <span style="font-weight: 700">{{ p.times }} 次</span>
        </button>
        <div class="small muted" style="margin-top: 10px">
          同一设备反复报修，建议进入更换评估名单。
        </div>

        <div class="asset-line">
          <button type="button" v-for="metric in assetMetrics" :key="metric.key" :data-metric="metric.key" aria-haspopup="dialog" :aria-label="'查看' + metric.label + '明细'" @click="selection = { kind: 'assets', ...metric }"><b>{{ ov[metric.key] || 0 }}</b><span>{{ metric.label }}</span></button>
        </div>
      </div>
    </div>
    <StatsAssetDialog v-if="selection" :kind="selection.kind" :label="selection.label" :asset-no="selection.assetNo" :status="selection.status" @close="selection = null" @count="updateCount" />
  </div>
</template>

<style scoped>
.timing-kpis { grid-template-columns:repeat(auto-fit,minmax(134px,1fr)); }
.page-h {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
}
.page-h h2 {
  font-size: 20px;
}
.grid2 {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 12px;
  margin-top: 12px;
}
.panel {
  background: #fff;
  border-radius: var(--r);
  padding: 14px 16px;
  box-shadow: var(--shadow);
}
.ph {
  margin-bottom: 6px;
}
.chart {
  height: 210px;
  width: 100%;
}
.prob {
  width:100%;
  background:transparent;
  border:0;
  text-align:left;
  cursor:pointer;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 0;
  border-bottom: 1px solid var(--line2);
}
.prob:hover { background:#f3f8ff; }.prob .mono { min-width:0; overflow:hidden; text-overflow:ellipsis; }.prob:focus-visible,.asset-line button:focus-visible { outline:2px solid var(--brand); outline-offset:2px; }
.prob:last-of-type {
  border-bottom: none;
}
.rk {
  width: 18px;
  height: 18px;
  border-radius: 5px;
  background: var(--brand-l);
  color: var(--brand);
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}
.asset-line {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--line2);
}
.asset-line button {
  flex: 1;
  min-width:0;
  text-align: center;
  background:transparent;
  border:0;
  border-radius:10px;
  padding:6px 2px;
  cursor:pointer;
}
.asset-line button:hover { background:#eef6ff; }.asset-line button:hover b { color:var(--brand); }
.asset-line b {
  display: block;
  font-size: 17px;
}
.asset-line span {
  font-size: 11.5px;
  color: var(--t3);
}
@media (max-width: 1000px) {
  .grid2 {
    grid-template-columns: 1fr;
  }
}
</style>
