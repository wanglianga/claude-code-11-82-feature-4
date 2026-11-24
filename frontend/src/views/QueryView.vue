<template>
  <div>
    <h2 class="page-title">交互查询审查</h2>
    <p class="page-desc">
      查询解析器规范化筛选/分组/时间窗/输出并与批准范围相交；推断审查器对所有已披露输出 + 公开总数做联合可行性分析，先干跑看风险再正式提交。
    </p>

    <div class="card">
      <div class="form-row">
        <div>
          <label>分析版本</label>
          <select v-model="form.analysisVersionId">
            <option v-for="v in versions" :key="v.id" :value="v.id">
              {{ v.approvalCode }} · v{{ v.versionNo }} · {{ v.algorithmVersion }}
            </option>
          </select>
        </div>
        <div>
          <label>分组维度（可多选，逗号分隔）</label>
          <input v-model="groupsText" placeholder="hospital 或 hospital,sex" />
        </div>
        <div>
          <label>度量</label>
          <select v-model="form.measures" multiple size="2">
            <option>PATIENT_COUNT</option>
            <option>VISIT_COUNT</option>
          </select>
        </div>
      </div>
      <div class="form-row mt">
        <div>
          <label>最小发布单元（≥空间阈值 5）</label>
          <input type="number" v-model.number="form.output.minUnit" min="5" />
        </div>
        <div>
          <label>舍入步长（1=精确披露）</label>
          <input type="number" v-model.number="roundingStep" min="1" />
        </div>
        <div>
          <label>备注（不参与规范化哈希）</label>
          <input v-model="form.comment" placeholder="查询文本不同不改变预算身份" />
        </div>
      </div>
      <div class="mt flex-between">
        <div class="small">规范化后查询身份由字段语义决定，与备注、版本号无关；同语义重复提交零扣点。</div>
        <div>
          <button class="btn-ghost" @click="dryRun">干跑（不落账）</button>
          <button class="btn-primary" @click="submit">正式提交</button>
        </div>
      </div>
      <p v-if="error" class="alert bad">{{ error }}</p>
    </div>

    <div v-if="outcome" class="card mt">
      <h3>
        审查裁决：
        <span class="badge" :class="outcome.decision === 'ALLOWED' ? 'ok' : 'bad'">{{ outcome.decision }}</span>
        <span v-if="outcome.duplicate" class="badge info">同语义已放行·零扣点复用</span>
      </h3>
      <div class="grid cols-3">
        <div><div class="kpi">{{ outcome.report.riskPoints }}</div><div class="kpi-label">本次风险点</div></div>
        <div><div class="kpi">{{ outcome.report.remainingBefore }}</div><div class="kpi-label">提交前季度剩余</div></div>
        <div><div class="kpi">{{ outcome.report.protectedCells?.length || 0 }}</div><div class="kpi-label">受保护单元数</div></div>
      </div>

      <div v-if="outcome.report.blockReasons?.length" class="alert bad mt">
        <b>阻断原因</b>
        <ul class="tight">
          <li v-for="(r, i) in outcome.report.blockReasons" :key="i">{{ r }}</li>
        </ul>
      </div>

      <h3 class="mt">受保护单元可行整数集合（联合推断）</h3>
      <table>
        <thead><tr><th>作用域</th><th>单元变量</th><th>审查前可行集</th><th>审查后可行集</th><th>窄化值数</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="(c, i) in outcome.report.protectedCells" :key="i">
            <td class="mono small">{{ c.scope }}</td>
            <td class="mono">{{ c.variable }}</td>
            <td class="mono">{{ c.baselineDomain.join(', ') }}</td>
            <td class="mono"><b :class="c.postDomain.length === 1 ? 'tag-suppressed' : ''">{{ c.postDomain.join(', ') }}</b></td>
            <td>{{ c.narrowedValues }}</td>
            <td>
              <span v-if="c.postDomain.length === 1" class="badge bad">唯一·硬阻断</span>
              <span v-else class="badge warn">仍多值</span>
            </td>
          </tr>
          <tr v-if="!outcome.report.protectedCells?.length"><td colspan="6" class="muted">无受保护小单元</td></tr>
        </tbody>
      </table>

      <h3 class="mt">对外输出（已脱敏，无患者级记录）</h3>
      <table>
        <thead><tr><th>维度单元</th><th>状态</th><th>披露计数</th><th>访视数</th><th>平均最大径(mm)</th></tr></thead>
        <tbody>
          <tr v-for="(c, i) in outcome.output" :key="i">
            <td class="mono">{{ c.dimKey }}</td>
            <td>
              <span v-if="c.status === 'SUPPRESSED'" class="tag-suppressed">SUPPRESSED</span>
              <span v-else class="badge" :class="c.status === 'EXACT' ? 'ok' : 'warn'">{{ c.status }}</span>
            </td>
            <td>{{ c.displayCount === null ? '— 抑制 —' : c.displayCount }}</td>
            <td>{{ c.visitCount ?? '—' }}</td>
            <td>{{ c.avgMaxMm ?? '—' }}</td>
          </tr>
        </tbody>
      </table>

      <div v-if="outcome.attemptId && outcome.decision === 'ALLOWED'" class="mt">
        <button class="btn-primary btn-sm" @click="freeze">冻结为交付物（attempt #{{ outcome.attemptId }}）</button>
        <span v-if="frozen" class="badge ok" style="margin-left: 10px">已冻结 {{ frozen.code }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import api from '../api'

const versions = ref([])
const groupsText = ref('hospital')
const roundingStep = ref(1)
const outcome = ref(null)
const error = ref('')
const frozen = ref(null)
const form = reactive({
  analysisVersionId: null,
  measures: ['PATIENT_COUNT'],
  output: { minUnit: 5 },
  comment: ''
})

async function loadVersions() {
  const { data: approvals } = await api.get('/approvals')
  const lists = await Promise.all(approvals.map((a) => api.get(`/approvals/${a.id}/versions`)))
  versions.value = lists.flatMap((r) => r.data)
  if (versions.value.length) form.analysisVersionId = versions.value[0].id
}
function buildRequest() {
  return {
    analysisVersionId: form.analysisVersionId,
    route: 'INTERACTIVE',
    groups: groupsText.value.split(',').map((s) => s.trim()).filter(Boolean),
    measures: form.measures,
    output: {
      minUnit: form.output.minUnit,
      ...(roundingStep.value > 1 ? { roundingStep: roundingStep.value } : {})
    },
    comment: form.comment
  }
}
async function dryRun() {
  error.value = ''
  try {
    const { data } = await api.post('/queries/dry-run', buildRequest())
    outcome.value = data
    frozen.value = null
  } catch (e) {
    error.value = e.response?.data?.error || e.message
    outcome.value = null
  }
}
async function submit() {
  error.value = ''
  try {
    const { data } = await api.post('/queries', buildRequest())
    outcome.value = data
    frozen.value = null
  } catch (e) {
    error.value = e.response?.data?.error || e.message
  }
}
async function freeze() {
  const { data } = await api.post('/deliverables', {
    attemptId: outcome.value.attemptId, title: '交互查询冻结结果', kind: 'TABLE'
  })
  frozen.value = data
}
onMounted(loadVersions)
</script>
