<template>
  <div>
    <h2 class="page-title">队列复现</h2>
    <p class="page-desc">队列由批准编译规格 + 数据水线确定性生成；同批准同水线重复编译得到同一 cohortHash，复现器据此重建已发布结果。</p>

    <div class="card">
      <div class="form-row">
        <div>
          <label>选择分析版本</label>
          <select v-model="versionId" @change="loadCohort">
            <option v-for="v in allVersions" :key="v.id" :value="v.id">
              {{ v.approvalCode }} · v{{ v.versionNo }} · {{ v.waterlineLabel }} · {{ v.algorithmVersion }}
            </option>
          </select>
        </div>
        <div style="flex: 0">
          <button class="btn-primary" :disabled="!versionId" @click="loadCohort">编译/复现队列</button>
        </div>
      </div>
    </div>

    <div v-if="cohort" class="card mt">
      <h3>队列结果（{{ cohort.memberCount }} 例脱敏成员）</h3>
      <div class="grid cols-3">
        <div><div class="kpi">{{ cohort.memberCount }}</div><div class="kpi-label">成员数（脱敏键）</div></div>
        <div><div class="kpi mono" style="font-size: 14px">{{ cohort.cohortHash.slice(0, 20) }}…</div>
          <div class="kpi-label">cohortHash（确定性）</div></div>
        <div><div class="kpi">{{ cohort.waterlineLabel }}</div><div class="kpi-label">绑定水线</div></div>
      </div>
      <h3 class="mt">脱敏成员键（仅伪键，无患者级身份）</h3>
      <div class="small mono">{{ cohort.memberKeys.join('、') }}</div>
      <h3 class="mt">队列编译规格</h3>
      <pre class="json">{{ JSON.stringify(JSON.parse(cohort.cohortSpec), null, 2) }}</pre>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const allVersions = ref([])
const versionId = ref(null)
const cohort = ref(null)

async function loadVersions() {
  const { data: approvals } = await api.get('/approvals')
  const lists = await Promise.all(approvals.map((a) => api.get(`/approvals/${a.id}/versions`)))
  allVersions.value = lists.flatMap((r) => r.data)
  if (allVersions.value.length) versionId.value = allVersions.value[0].id
}
async function loadCohort() {
  if (!versionId.value) return
  const { data } = await api.get(`/versions/${versionId.value}/cohort`)
  cohort.value = data
}
onMounted(loadVersions)
</script>
