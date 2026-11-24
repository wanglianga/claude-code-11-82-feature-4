<template>
  <div>
    <h2 class="page-title">交付与复现台账</h2>
    <p class="page-desc">
      交付器冻结结果、算法版本、批准快照、水线与审查证明（含 sha256）。复现器按当时审批与数据水线重建；撤回/到期后旧交付物仍按留存许可审计，但不开放新查询。
    </p>

    <div class="card">
      <table>
        <thead><tr><th>编号</th><th>标题</th><th>类型</th><th>状态</th><th>冻结时间</th><th>sha256</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="d in deliverables" :key="d.id">
            <td class="mono">{{ d.code }}</td>
            <td>{{ d.title }}</td>
            <td><span class="badge info">{{ d.kind }}</span></td>
            <td><span class="badge" :class="d.status === 'FROZEN' ? 'ok' : 'gray'">{{ d.status }}</span></td>
            <td class="small">{{ fmt(d.createdAt) }}</td>
            <td class="mono small">{{ d.payloadSha256.slice(0, 14) }}…</td>
            <td>
              <button class="btn-ghost btn-sm" @click="show(d)">清单/载荷</button>
              <button class="btn-primary btn-sm" @click="reproduce(d)">按水线复现</button>
            </td>
          </tr>
          <tr v-if="!deliverables.length"><td colspan="7" class="muted">暂无交付物，可在“交互查询审查”中冻结已放行结果</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="current" class="card mt">
      <h3>{{ current.code }} · 冻结清单</h3>
      <pre class="json">{{ manifest }}</pre>
      <h3 class="mt">冻结载荷（脱敏输出）</h3>
      <pre class="json">{{ payload }}</pre>
    </div>

    <div v-if="reproResult" class="card mt">
      <h3>复现结论
        <span class="badge" :class="reproResult.conclusion.startsWith('REPRODUCED') ? 'ok' : 'bad'">
          {{ reproResult.conclusion.split('：')[0] }}
        </span>
      </h3>
      <table>
        <tbody>
          <tr><th>批准当前可读</th><td>{{ reproResult.approvalReadableNow ? '是' : '否（撤回/到期，仅供审计）' }}</td></tr>
          <tr><th>队列哈希一致</th><td>{{ reproResult.cohortHashMatch ? '是' : '否' }}</td></tr>
          <tr><th>载荷哈希完整</th><td>{{ reproResult.payloadHashIntact ? '是' : '否' }}</td></tr>
          <tr><th>逐单元输出一致</th><td>{{ reproResult.outputMatch ? '是' : '否' }}</td></tr>
          <tr><th>当前成员数</th><td>{{ reproResult.memberCountNow }}</td></tr>
        </tbody>
      </table>
      <div class="grid cols-2 mt">
        <div><h3>冻结输出</h3><pre class="json">{{ JSON.stringify(reproResult.frozenOutput, null, 2) }}</pre></div>
        <div><h3>重建输出</h3><pre class="json">{{ JSON.stringify(reproResult.reproducedOutput, null, 2) }}</pre></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const deliverables = ref([])
const current = ref(null)
const manifest = ref('')
const payload = ref('')
const reproResult = ref(null)

function fmt(ms) { return new Date(ms).toLocaleString() }
async function load() {
  const spaces = await api.get('/admin/spaces')
  const lists = await Promise.all(spaces.data.map((s) => api.get(`/deliverables?spaceId=${s.id}`)))
  deliverables.value = lists.flatMap((r) => r.data)
}
async function show(d) {
  current.value = d
  reproResult.value = null
  manifest.value = JSON.stringify(JSON.parse(d.manifestJson), null, 2)
  payload.value = JSON.stringify(JSON.parse(d.frozenPayloadJson), null, 2)
}
async function reproduce(d) {
  current.value = d
  manifest.value = JSON.stringify(JSON.parse(d.manifestJson), null, 2)
  payload.value = JSON.stringify(JSON.parse(d.frozenPayloadJson), null, 2)
  const { data } = await api.post(`/deliverables/${d.id}/reproduce`, {})
  reproResult.value = data
}
onMounted(load)
</script>
