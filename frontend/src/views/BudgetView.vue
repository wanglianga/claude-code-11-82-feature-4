<template>
  <div>
    <h2 class="page-title">披露预算与联合约束图</h2>
    <p class="page-desc">
      预算按研究空间 + 季度累计，只随受保护单元可行整数集合窄化而消耗，不因查询文本或版本号重置；最小发布单元 5，每季度 10 风险点，唯一计数无论余额一律硬阻断。
    </p>

    <div class="grid cols-2">
      <div class="card">
        <h3>季度预算账本</h3>
        <table>
          <thead><tr><th>季度</th><th>上限</th><th>已用</th><th>剩余</th><th>使用率</th></tr></thead>
          <tbody>
            <tr v-for="l in ledger" :key="l.id">
              <td class="mono">{{ l.quarter }}</td>
              <td>{{ l.riskCap }}</td>
              <td><b :class="l.pointsSpent >= l.riskCap ? 'tag-suppressed' : ''">{{ l.pointsSpent }}</b></td>
              <td>{{ l.riskCap - l.pointsSpent }}</td>
              <td>
                <div style="background:#f1f5f9;border-radius:6px;height:10px;width:120px;overflow:hidden">
                  <div :style="{ width: pct(l) + '%', background: pct(l) >= 100 ? '#dc2626' : '#1d4ed8', height: '100%' }"></div>
                </div>
                <span class="small">{{ pct(l) }}%</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="auth.isAdmin" class="mt form-row">
          <div>
            <label>治理：调整季度风险上限</label>
            <input type="number" v-model.number="newCap" min="1" />
          </div>
          <div style="flex:0">
            <button class="btn-primary" @click="updateCap">应用</button>
          </div>
        </div>
      </div>

      <div class="card">
        <h3>规则说明</h3>
        <ul class="tight small">
          <li>每新增一个结果，令某受保护单元的可行整数计数集合少一个可能值，即消耗 1 风险点。</li>
          <li>任何单元被联合约束为唯一计数：无论剩余预算多少，阻断或改变输出（舍入/抑制）。</li>
          <li>风险点耗尽后，仅放行零窄化结果（候选约束与历史完全重合，增量为 0）。</li>
          <li>阻断不扣点、不留约束；同语义重复查询幂等零扣点。</li>
        </ul>
      </div>
    </div>

    <div class="card mt">
      <h3>联合约束图（所有已披露输出 + 公开总数 + 抑制承诺 + 分区恒等）</h3>
      <table>
        <thead><tr><th>ID</th><th>类型</th><th>系数（作用域::变量）</th><th>运算符</th><th>右端值</th></tr></thead>
        <tbody>
          <tr v-for="c in constraints" :key="c.id">
            <td>{{ c.id }}</td>
            <td><span class="badge" :class="kindClass(c.constraintKind)">{{ c.constraintKind }}</span></td>
            <td class="mono small">{{ c.coeffsJson }}</td>
            <td class="mono">{{ c.operator }}</td>
            <td class="mono">{{ c.rhs }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'
import { useAuthStore } from '../store'

const auth = useAuthStore()
const ledger = ref([])
const constraints = ref([])
const newCap = ref(10)
const SPACE_ID = 1

function pct(l) { return Math.round((l.pointsSpent / l.riskCap) * 100) }
function kindClass(k) {
  return {
    PUBLIC_TOTAL: 'info', DISCLOSED_EQUALITY: 'ok', SUPPRESSION_DOMAIN: 'bad',
    ROUNDED_INTERVAL: 'warn', STRUCTURAL_PARTITION: 'gray', FIXED_TABLE_SET: 'info'
  }[k] || 'gray'
}
async function load() {
  const [b, c] = await Promise.all([
    api.get(`/spaces/${SPACE_ID}/budget`),
    api.get(`/spaces/${SPACE_ID}/constraints`)
  ])
  ledger.value = b.data
  constraints.value = c.data
}
async function updateCap() {
  await api.post(`/admin/spaces/${SPACE_ID}/budget-cap`, { quarterlyBudget: newCap.value })
  await load()
}
onMounted(load)
</script>
