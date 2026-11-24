<template>
  <div>
    <h2 class="page-title">批准与分析版本</h2>
    <p class="page-desc">批准编译为确定性规格（specHash），目的或水线改变须新建分析版本；撤回/到期后拒绝一切新读取。</p>

    <div class="card">
      <h3>数据批准列表</h3>
      <table>
        <thead>
          <tr><th>编号</th><th>标题</th><th>中心/目的</th><th>有效期</th><th>状态</th><th>specHash</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="a in approvals" :key="a.id">
            <td class="mono">{{ a.code }}</td>
            <td>{{ a.title }}</td>
            <td class="small">{{ a.center }}<br />{{ a.purpose }}</td>
            <td class="small">{{ fmt(a.validFrom) }}<br />~ {{ fmt(a.validTo) }}</td>
            <td><span class="badge" :class="statusClass(a.status)">{{ a.status }}</span></td>
            <td class="mono small">{{ a.specHash.slice(0, 12) }}…</td>
            <td>
              <button class="btn-ghost btn-sm" @click="loadVersions(a)">版本</button>
              <button v-if="auth.isAdmin && a.status === 'ACTIVE'" class="btn-danger btn-sm"
                      @click="revoke(a)">撤回</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="current" class="card mt">
      <h3>分析版本 · {{ current.code }}（{{ current.title }}）</h3>
      <div class="form-row">
        <div>
          <label>研究目的</label>
          <input v-model="newVersion.purpose" placeholder="须与批准目的一致" />
        </div>
        <div>
          <label>数据水线</label>
          <select v-model="newVersion.waterlineLabel">
            <option v-for="w in waterlines" :key="w.label" :value="w.label">{{ w.label }}</option>
          </select>
        </div>
        <div>
          <label>特征算法版本</label>
          <select v-model="newVersion.algorithmVersion">
            <option>feat-v1</option>
            <option>feat-v2</option>
          </select>
        </div>
        <div style="flex: 0">
          <button class="btn-primary" @click="createVersion">新建版本</button>
        </div>
      </div>
      <p v-if="versionError" class="alert bad">{{ versionError }}</p>
      <table class="mt">
        <thead><tr><th>版本</th><th>水线</th><th>算法</th><th>目的</th><th>状态</th><th>specHash</th></tr></thead>
        <tbody>
          <tr v-for="v in versions" :key="v.id">
            <td>v{{ v.versionNo }}</td>
            <td class="mono">{{ v.waterlineLabel }}</td>
            <td class="mono">{{ v.algorithmVersion }}</td>
            <td class="small">{{ v.purpose }}</td>
            <td><span class="badge info">{{ v.status }}</span></td>
            <td class="mono small">{{ v.specHash.slice(0, 12) }}…</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import api from '../api'
import { useAuthStore } from '../store'

const auth = useAuthStore()
const approvals = ref([])
const versions = ref([])
const waterlines = ref([])
const current = ref(null)
const versionError = ref('')
const newVersion = reactive({ purpose: '中心医院联合影像研究', waterlineLabel: 'WL-2026Q3', algorithmVersion: 'feat-v1' })

function fmt(ms) { return new Date(ms).toISOString().slice(0, 10) }
function statusClass(s) {
  return { ACTIVE: 'ok', DRAFT: 'gray', REVOKED: 'bad', EXPIRED: 'warn' }[s] || 'gray'
}
async function load() {
  const [a, w] = await Promise.all([api.get('/approvals'), api.get('/admin/waterlines')])
  approvals.value = a.data
  waterlines.value = w.data
}
async function loadVersions(a) {
  current.value = a
  versionError.value = ''
  const { data } = await api.get(`/approvals/${a.id}/versions`)
  versions.value = data
}
async function createVersion() {
  versionError.value = ''
  try {
    await api.post(`/approvals/${current.value.id}/versions`, { ...newVersion })
    await loadVersions(current.value)
  } catch (e) {
    versionError.value = e.response?.data?.error || e.message
  }
}
async function revoke(a) {
  const reason = prompt('撤回原因', '撤回字段授权')
  if (!reason) return
  await api.post(`/admin/approvals/${a.id}/revoke`, { reason })
  await load()
  if (current.value?.id === a.id) await loadVersions(a)
}
onMounted(load)
</script>
