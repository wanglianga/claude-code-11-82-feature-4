<template>
  <div>
    <h2 class="page-title">场景验证导览</h2>
    <p class="page-desc">
      以下三个场景直接调用后端审查链路，复现题目规定的联合推断阻断、安全披露与撤回治理。所有数据为虚构数据，不含原始影像或患者级记录。
    </p>

    <div class="grid">
      <section class="card scenario-card s1">
        <h3>场景一 · 队列 15 例（医院 A=14、B=1），公开总数 15</h3>
        <p class="small">
          “总数表”与“医院 A 表”分别过最小单元检查，但相减可唯一推出 B=1。系统须识别联合约束并阻断至少一项，或改用不泄露的舍入/抑制输出。
        </p>
        <button class="btn-primary btn-sm" :disabled="running" @click="runScenario1">
          {{ running ? '运行中…' : '运行场景一' }}
        </button>
        <div v-if="s1.logs.length" class="mt">
          <div v-for="(l, i) in s1.logs" :key="i" class="alert" :class="l.level">
            <b>[{{ l.step }}]</b> {{ l.text }}
            <pre v-if="l.detail" class="json">{{ l.detail }}</pre>
          </div>
        </div>
      </section>

      <section class="card scenario-card s2">
        <h3>场景二 · 队列 15 例（医院 A=8、B=7），无额外边际</h3>
        <p class="small">两单元均 ≥ 最小发布单元 5，不产生受保护小单元，应在不超预算时保留可用结果，并可冻结、按当时水线复现。</p>
        <button class="btn-primary btn-sm" :disabled="running" @click="runScenario2">
          {{ running ? '运行中…' : '运行场景二' }}
        </button>
        <div v-if="s2.logs.length" class="mt">
          <div v-for="(l, i) in s2.logs" :key="i" class="alert" :class="l.level">
            <b>[{{ l.step }}]</b> {{ l.text }}
            <pre v-if="l.detail" class="json">{{ l.detail }}</pre>
          </div>
        </div>
      </section>

      <section class="card scenario-card s3">
        <h3>场景三 · 撤回字段授权 / 审批到期</h3>
        <p class="small">撤回或到期后所有新读取被拒绝（403）；旧交付物仅按原留存许可供审计读取与复现，不再开放查询。</p>
        <button class="btn-primary btn-sm" :disabled="running" @click="runScenario3">
          {{ running ? '运行中…' : '运行场景三' }}
        </button>
        <div v-if="s3.logs.length" class="mt">
          <div v-for="(l, i) in s3.logs" :key="i" class="alert" :class="l.level">
            <b>[{{ l.step }}]</b> {{ l.text }}
            <pre v-if="l.detail" class="json">{{ l.detail }}</pre>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import api from '../api'

const running = ref(false)
const s1 = reactive({ logs: [] })
const s2 = reactive({ logs: [] })
const s3 = reactive({ logs: [] })

function log(bucket, level, step, text, detail) {
  bucket.logs.push({ level, step, text, detail: detail ? JSON.stringify(detail, null, 2) : '' })
}
async function approvals() {
  const { data } = await api.get('/approvals')
  return Object.fromEntries(data.map((a) => [a.code, a.id]))
}
async function version(approvalId, waterlineLabel, algorithmVersion = 'feat-v1') {
  const { data } = await api.post(`/approvals/${approvalId}/versions`, {
    purpose: '中心医院联合影像研究',
    waterlineLabel,
    algorithmVersion
  })
  return data
}
async function expectFail(promise) {
  try {
    await promise
    return null
  } catch (e) {
    return { code: e.response?.status, error: e.response?.data?.error }
  }
}

async function runScenario1() {
  running.value = true
  s1.logs = []
  try {
    const map = await approvals()
    const v = await version(map['APV-S1'], 'WL-2026Q3')
    log(s1, 'ok', '1', '建立分析版本（feat-v1 / WL-2026Q3）', { versionId: v.id })

    const total = await api.post('/queries', { analysisVersionId: v.id, measures: ['PATIENT_COUNT'] })
    log(s1, 'ok', '2', '总数表放行（TOTAL=15，0 风险点）', total.data.report)

    const hospDry = await api.post('/queries/dry-run', {
      analysisVersionId: v.id, groups: ['hospital'], measures: ['PATIENT_COUNT']
    })
    const b = hospDry.data.report.protectedCells?.find((c) => c.variable === 'hospital=B')
    log(s1, hospDry.data.decision === 'WOULD_BLOCK' ? 'bad' : 'ok', '3',
      '医院表联合推断：B 被相减唯一推出，硬阻断',
      { decision: hospDry.data.decision, blockReasons: hospDry.data.report.blockReasons, B: b })

    const hospSubmit = await api.post('/queries', {
      analysisVersionId: v.id, groups: ['hospital'], measures: ['PATIENT_COUNT']
    })
    log(s1, 'bad', '4', '正式提交裁决=' + hospSubmit.data.decision + '（不扣预算、不留约束）',
      { riskPoints: hospSubmit.data.report.riskPoints })

    const rounded = await api.post('/queries', {
      analysisVersionId: v.id, groups: ['hospital'], measures: ['PATIENT_COUNT'],
      output: { minUnit: 5, roundingStep: 5 }
    })
    const br = rounded.data.report.protectedCells?.find((c) => c.variable === 'hospital=B')
    log(s1, 'ok', '5', '不泄露替代：舍入到 5 且抑制 B，B 可行集不再唯一',
      { output: rounded.data.output, B_domain: br?.postDomain, points: rounded.data.report.riskPoints })
  } catch (e) {
    log(s1, 'bad', 'X', '异常', e.response?.data?.error || e.message)
  } finally {
    running.value = false
  }
}

async function runScenario2() {
  running.value = true
  s2.logs = []
  try {
    const map = await approvals()
    const v = await version(map['APV-S2'], 'WL-2026Q4')
    log(s2, 'ok', '1', '建立分析版本（feat-v1 / WL-2026Q4）', { versionId: v.id })
    const cohort = await api.get(`/versions/${v.id}/cohort`)
    log(s2, 'ok', '2', `队列复现 ${cohort.data.memberCount} 例，cohortHash=${cohort.data.cohortHash.slice(0, 16)}…`)

    const hosp = await api.post('/queries', {
      analysisVersionId: v.id, groups: ['hospital'], measures: ['PATIENT_COUNT']
    })
    log(s2, 'ok', '3', '医院表放行 A=8、B=7（无小单元，可用结果保留）',
      { output: hosp.data.output, points: hosp.data.report.riskPoints })

    const dlv = await api.post('/deliverables', {
      attemptId: hosp.data.attemptId, title: 'S2 医院分布冻结表', kind: 'TABLE'
    })
    log(s2, 'ok', '4', '交付冻结，payloadSha256=' + dlv.data.payloadSha256.slice(0, 16) + '…')

    const repro = await api.post(`/deliverables/${dlv.data.id}/reproduce`, {})
    log(s2, repro.data.conclusion.startsWith('REPRODUCED') ? 'ok' : 'bad', '5',
      '按当时审批与水线复现：' + repro.data.conclusion,
      { outputMatch: repro.data.outputMatch, cohortHashMatch: repro.data.cohortHashMatch })
  } catch (e) {
    log(s2, 'bad', 'X', '异常', e.response?.data?.error || e.message)
  } finally {
    running.value = false
  }
}

async function runScenario3() {
  running.value = true
  s3.logs = []
  try {
    const map = await approvals()
    const v = await version(map['APV-S2'], 'WL-2026Q4')
    const hosp = await api.post('/queries', {
      analysisVersionId: v.id, groups: ['hospital'], measures: ['PATIENT_COUNT']
    })
    const dlv = await api.post('/deliverables', {
      attemptId: hosp.data.attemptId, title: '撤回前冻结表', kind: 'TABLE'
    })
    log(s3, 'ok', '1', '撤回前先冻结一份交付物（留存审计用）', { deliverable: dlv.data.code })

    const revoke = await api.post(`/admin/approvals/${map['APV-S2']}/revoke`,
      { reason: '撤回医院 B 字段授权' })
    log(s3, 'warn', '2', '批准状态变更为 ' + revoke.data.status)

    const denied = await expectFail(api.post('/queries', {
      analysisVersionId: v.id, measures: ['PATIENT_COUNT']
    }))
    log(s3, denied?.code === 403 ? 'bad' : 'ok', '3', '撤回后新查询被拒绝', denied)

    const old = await api.get(`/deliverables/${dlv.data.id}`)
    const repro = await api.post(`/deliverables/${dlv.data.id}/reproduce`, {})
    log(s3, 'ok', '4', '旧交付物仍可审计读取与复现（只读留存）',
      { status: old.data.status, conclusion: repro.data.conclusion,
        approvalReadableNow: repro.data.approvalReadableNow })

    const expiredMap = await approvals()
    const expired = await expectFail(api.post(`/approvals/${expiredMap['APV-EXPIRED']}/versions`,
      { purpose: '历史回顾研究', waterlineLabel: 'WL-2026Q3' }))
    log(s3, expired?.code === 403 ? 'bad' : 'ok', '5', '到期批准新建版本被拒绝', expired)
  } catch (e) {
    log(s3, 'bad', 'X', '异常', e.response?.data?.error || e.message)
  } finally {
    running.value = false
  }
}
</script>
