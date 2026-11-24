<template>
  <div>
    <h2 class="page-title">固定表组联合核验</h2>
    <p class="page-desc">
      对一组互不细分的安全统计表预先编译、整组联合推断。任一组表导致唯一计数或预算超限即全组阻断（全有或全无）；边界稳定、审计简单，新问题须等待下次批准。
    </p>

    <div class="card">
      <div class="form-row">
        <div>
          <label>分析版本</label>
          <select v-model="versionId">
            <option v-for="v in versions" :key="v.id" :value="v.id">
              {{ v.approvalCode }} · v{{ v.versionNo }}
            </option>
          </select>
        </div>
      </div>
      <h3 class="mt">组内查询（按“添加表”生成，至少含一张分组表与一张总数表以演示联合推断）</h3>
      <table>
        <thead><tr><th>#</th><th>分组维度</th><th>最小单元</th><th>舍入步长</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="(q, i) in queries" :key="i">
            <td>{{ i + 1 }}</td>
            <td><input v-model="q.groups" placeholder="hospital（留空=总数表）" /></td>
            <td><input type="number" v-model.number="q.minUnit" min="5" style="width: 90px" /></td>
            <td><input type="number" v-model.number="q.roundingStep" min="1" style="width: 90px" /></td>
            <td><button class="btn-danger btn-sm" @click="queries.splice(i, 1)">移除</button></td>
          </tr>
        </tbody>
      </table>
      <div class="mt flex-between">
        <div>
          <button class="btn-ghost btn-sm" @click="addQuery('hospital')">添加分组表</button>
          <button class="btn-ghost btn-sm" @click="addQuery('')">添加总数表</button>
        </div>
        <div>
          <button class="btn-ghost" @click="run(true)">干跑整组</button>
          <button class="btn-primary" @click="run(false)">整组提交</button>
        </div>
      </div>
      <p v-if="error" class="alert bad">{{ error }}</p>
    </div>

    <div v-if="outcome" class="card mt">
      <h3>整组裁决：<span class="badge" :class="outcome.decision.includes('ALLOW') ? 'ok' : 'bad'">
        {{ outcome.decision }}</span></h3>
      <div class="grid cols-3">
        <div><div class="kpi">{{ outcome.report.riskPoints }}</div><div class="kpi-label">整组风险点（一次计）</div></div>
        <div><div class="kpi">{{ outcome.report.candidateConstraints }}</div><div class="kpi-label">组内候选约束</div></div>
        <div><div class="kpi">{{ outcome.report.historicalConstraints }}</div><div class="kpi-label">历史累计约束</div></div>
      </div>
      <div v-if="outcome.report.blockReasons?.length" class="alert bad mt">
        <ul class="tight"><li v-for="(r, i) in outcome.report.blockReasons" :key="i">{{ r }}</li></ul>
      </div>
      <h3 class="mt">受保护单元联合可行集</h3>
      <table>
        <thead><tr><th>单元</th><th>审查前</th><th>审查后</th><th>窄化</th></tr></thead>
        <tbody>
          <tr v-for="(c, i) in outcome.report.protectedCells" :key="i">
            <td class="mono">{{ c.variable }}</td>
            <td class="mono small">{{ c.baselineDomain.join(', ') }}</td>
            <td class="mono">{{ c.postDomain.join(', ') }}</td>
            <td>{{ c.narrowedValues }}</td>
          </tr>
          <tr v-if="!outcome.report.protectedCells?.length"><td colspan="4" class="muted">无受保护单元</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const versions = ref([])
const versionId = ref(null)
const queries = ref([
  { groups: '', minUnit: 5, roundingStep: 1 },
  { groups: 'hospital', minUnit: 5, roundingStep: 1 }
])
const outcome = ref(null)
const error = ref('')

async function loadVersions() {
  const { data: approvals } = await api.get('/approvals')
  const lists = await Promise.all(approvals.map((a) => api.get(`/approvals/${a.id}/versions`)))
  versions.value = lists.flatMap((r) => r.data)
  if (versions.value.length) versionId.value = versions.value[0].id
}
function addQuery(groups) {
  queries.value.push({ groups, minUnit: 5, roundingStep: 1 })
}
async function run(dry) {
  error.value = ''
  const payload = {
    analysisVersionId: versionId.value,
    queries: queries.value.map((q) => ({
      analysisVersionId: versionId.value,
      route: 'FIXED_TABLE_SET',
      groups: q.groups ? q.groups.split(',').map((s) => s.trim()).filter(Boolean) : [],
      measures: ['PATIENT_COUNT'],
      output: { minUnit: q.minUnit, ...(q.roundingStep > 1 ? { roundingStep: q.roundingStep } : {}) }
    }))
  }
  try {
    const { data } = await api.post(dry ? '/table-sets/dry-run' : '/table-sets', payload)
    outcome.value = data
  } catch (e) {
    error.value = e.response?.data?.error || e.message
  }
}
onMounted(loadVersions)
</script>
