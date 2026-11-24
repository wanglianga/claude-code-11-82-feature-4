<template>
  <div>
    <h2 class="page-title">审计事件</h2>
    <p class="page-desc">批准编译/激活/撤回、查询审查与阻断、交付冻结、复现、会话等全部敏感动作留痕，支持按研究空间过滤。</p>

    <div class="card">
      <div class="flex-between">
        <h3 style="margin:0">事件流（最新在前）</h3>
        <div>
          <select v-model="spaceFilter" @change="load" style="width:220px">
            <option :value="null">全部空间</option>
            <option v-for="s in spaces" :key="s.id" :value="s.id">{{ s.code }} · {{ s.name }}</option>
          </select>
          <button class="btn-ghost btn-sm" style="margin-left:8px" @click="load">刷新</button>
        </div>
      </div>
      <table class="mt">
        <thead><tr><th>时间</th><th>空间</th><th>操作者</th><th>动作</th><th>详情</th></tr></thead>
        <tbody>
          <tr v-for="e in events" :key="e.id">
            <td class="small mono">{{ fmt(e.createdAt) }}</td>
            <td>{{ e.spaceId ?? '—' }}</td>
            <td class="mono">{{ e.actor }}</td>
            <td><span class="badge" :class="actionClass(e.action)">{{ e.action }}</span></td>
            <td class="small">{{ e.detail }}</td>
          </tr>
          <tr v-if="!events.length"><td colspan="5" class="muted">暂无审计事件</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const events = ref([])
const spaces = ref([])
const spaceFilter = ref(null)

function fmt(ms) { return new Date(ms).toLocaleString() }
function actionClass(a) {
  if (a.includes('BLOCKED') || a.includes('REVOKE') || a.includes('DENIED')) return 'bad'
  if (a.includes('FREEZE') || a.includes('ACTIVATE') || a.includes('REPRODUCTION')) return 'ok'
  return 'info'
}
async function load() {
  const url = spaceFilter.value ? `/audit?spaceId=${spaceFilter.value}` : '/audit'
  const { data } = await api.get(url)
  events.value = data
}
onMounted(async () => {
  const { data } = await api.get('/admin/spaces')
  spaces.value = data
  await load()
})
</script>
