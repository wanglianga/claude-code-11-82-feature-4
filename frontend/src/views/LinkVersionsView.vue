<template>
  <div>
    <h2 class="page-title">链接版本封存与受控重建</h2>
    <p class="page-desc">
      院外令牌链接分析发布时冻结“复现闭包”：提供方输入批次、令牌化规则版本、候选阻断规则、用途授权快照、
      受控匹配权限与成员摘要。密钥轮换后，受控重建服务只按封存时的旧规则/旧授权快照重新生成成员，
      再与封存摘要逐项核对。两条路线（托管封条重放 / 提供方重建）都必须恢复原成员，取舍取决于
      提供方可用性、托管风险与重放频率。期限届满后删除逐例恢复能力，只留不可逆统计审计摘要。
    </p>

    <div class="grid cols-2">
      <section class="card">
        <h3>令牌密钥代际 <span class="badge gray">{{ keys.length }} 代</span></h3>
        <table>
          <thead><tr><th>kid</th><th>状态</th><th>指纹</th><th>托管引用</th></tr></thead>
          <tbody>
            <tr v-for="k in keys" :key="k.kid">
              <td class="mono small">{{ k.kid }}</td>
              <td><span class="badge" :class="k.status === 'ACTIVE' ? 'ok' : 'gray'">{{ k.status }}</span></td>
              <td class="mono small">{{ k.fingerprint }}</td>
              <td class="mono small">{{ k.escrowRef }}</td>
            </tr>
          </tbody>
        </table>
        <div class="mt flex-between">
          <div class="small">轮换只停用当前代际，旧代际材料保留在托管面，供封存包期限内的受控重建使用。</div>
          <button v-if="auth.isAdmin" class="btn-danger btn-sm" @click="rotateKey">轮换令牌密钥</button>
        </div>
      </section>

      <section class="card">
        <h3>提供方可用性 <span class="badge gray">{{ providers.length }} 家</span></h3>
        <table>
          <thead><tr><th>提供方</th><th>覆盖院区</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="p in providers" :key="p.providerCode">
              <td>{{ p.name }}<div class="mono small">{{ p.providerCode }}</div></td>
              <td class="mono">{{ p.coversHospital || '联合' }}</td>
              <td>
                <span class="badge" :class="p.status === 'AVAILABLE' ? 'ok' : 'bad'">{{ p.status }}</span>
                <button v-if="auth.isAdmin" class="btn-ghost btn-sm" style="margin-left:6px"
                        @click="toggleProvider(p)">
                  {{ p.status === 'AVAILABLE' ? '置为不可用' : '恢复可用' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="small mt">
          “提供方重建”路线在提供方不可用时会进入<b>证据不完整</b>，不会用替代输入改变成员；
          “托管封条”路线不依赖提供方在线。
        </div>
      </section>
    </div>

    <div class="card mt">
      <h3>封存新的链接版本</h3>
      <p class="small">
        选定已批准的分析与水线后封存复现闭包。路线二选一：托管封条（重放快、托管面大）或提供方重建
        （留存映射最少、依赖提供方可用）；也可选择 AUTO 按提供方可用性/托管风险/重放频率自动取舍。
      </p>
      <div class="form-row">
        <div>
          <label>批准</label>
          <select v-model="sealForm.approvalId" @change="onApprovalChange">
            <option v-for="a in sealApprovals" :key="a.id" :value="a.id">
              {{ a.code }} · {{ a.status }}
            </option>
          </select>
        </div>
        <div>
          <label>分析版本（决定水线）</label>
          <select v-model="sealForm.analysisVersionId">
            <option :value="null">（不绑定版本，默认 WL-2026Q4）</option>
            <option v-for="v in sealVersions" :key="v.id" :value="v.id">
              v{{ v.versionNo }} · {{ v.waterlineLabel }} · {{ v.algorithmVersion }}
            </option>
          </select>
        </div>
        <div>
          <label>提供方</label>
          <select v-model="sealForm.providerCode">
            <option v-for="p in providers" :key="p.providerCode" :value="p.providerCode">
              {{ p.providerCode }}（{{ p.coversHospital || '联合' }} · {{ p.status }}）
            </option>
          </select>
        </div>
        <div>
          <label>留存路线</label>
          <select v-model="sealForm.strategy">
            <option value="AUTO">AUTO（自动取舍）</option>
            <option value="ESCROW_SEAL">托管封条重放</option>
            <option value="PROVIDER_REBUILD">提供方重建</option>
          </select>
        </div>
        <div>
          <label>复现期限（分钟）</label>
          <input type="number" v-model.number="sealForm.reproduceWindowMinutes" min="1" />
        </div>
        <div style="flex: 0">
          <button class="btn-primary" :disabled="!sealForm.approvalId || !sealForm.providerCode"
                  @click="sealVersion">封存链接版本</button>
        </div>
      </div>
    </div>

    <div class="card mt">
      <h3>用途授权台账 <span class="badge gray">{{ purposeAuths.length }} 份快照</span></h3>
      <p class="small">
        用途授权快照是链接版本复现闭包的一部分。快照缺失或标记 MISSING 时，引用它的旧链接版本进入
        <b>证据不完整</b>，不会被新快照替换来解释成员；如需恢复复现能力，须由管理员按批准现有规格恢复快照。
      </p>
      <table>
        <thead><tr><th>快照</th><th>批准</th><th>目的</th><th>状态</th><th>snapshotHash</th><th v-if="auth.isAdmin">操作</th></tr></thead>
        <tbody>
          <tr v-for="pa in purposeAuths" :key="pa.id">
            <td class="mono small">{{ pa.code }}</td>
            <td class="mono">{{ pa.approvalCode }}</td>
            <td class="small">{{ pa.purpose }}</td>
            <td><span class="badge" :class="pa.status === 'ACTIVE' ? 'ok' : 'warn'">{{ pa.status }}</span></td>
            <td class="mono small">{{ pa.snapshotHash.slice(0, 16) }}…</td>
            <td v-if="auth.isAdmin">
              <button v-if="pa.status === 'ACTIVE'" class="btn-danger btn-sm"
                      @click="setPurposeAuth(pa, true)">模拟缺失</button>
              <button v-else class="btn-primary btn-sm"
                      @click="setPurposeAuth(pa, false)">授权恢复</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card mt">
      <h3>已批准链接版本（封存包）</h3>
      <table>
        <thead>
          <tr><th>编号</th><th>批准 / 版本</th><th>提供方</th><th>策略</th><th>成员</th>
              <th>复现期限</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="v in versions" :key="v.id">
            <td class="mono">{{ v.code }}</td>
            <td>{{ v.approvalCode }}<div class="small">v{{ v.analysisVersionNo }} · {{ v.purposeScope }}</div></td>
            <td class="mono">{{ v.providerCode }}</td>
            <td><span class="badge" :class="v.sealingStrategy === 'ESCROW_SEAL' ? 'info' : 'warn'">
              {{ v.sealingStrategy === 'ESCROW_SEAL' ? '托管封条重放' : '提供方重建' }}</span></td>
            <td>{{ v.memberCount }}</td>
            <td class="small">{{ v.windowOpen ? '开放至 ' + fmtShort(v.reproduceWindowEnd) : '已届满（' + fmtShort(v.reproduceWindowEnd) + '）' }}</td>
            <td><span class="badge" :class="statusClass(v.status)">{{ v.status }}</span></td>
            <td>
              <button class="btn-ghost btn-sm" @click="openLedger(v)">封存包/谱系</button>
              <button class="btn-primary btn-sm" :disabled="!v.windowOpen || v.status === 'EXPIRED'"
                      @click="rebuild(v, 'REPLAYED_OLD')">受控重建</button>
            </td>
          </tr>
          <tr v-if="!versions.length"><td colspan="8" class="muted">暂无链接版本</td></tr>
        </tbody>
      </table>
    </div>

    <p v-if="error" class="alert bad">{{ error }}</p>

    <div v-if="result" class="card mt">
      <h3>受控重建结论
        <span class="badge" :class="verdictClass(result.verdict)">{{ result.verdict }}</span>
        <span v-if="result.claimsOldExperimentReproduction" class="badge ok">可称为该旧实验的复现</span>
        <span v-else class="badge bad">禁止称为旧实验复现</span>
      </h3>
      <div class="grid cols-3">
        <div><div class="kpi">{{ result.rebuiltMemberCount }}</div><div class="kpi-label">重建成员数</div></div>
        <div><div class="kpi">{{ result.sealedMemberCount }}</div><div class="kpi-label">封存成员数</div></div>
        <div><div class="kpi">{{ result.digestRootMatch ? '✓' : '✗' }}</div><div class="kpi-label">摘要根一致</div></div>
      </div>
      <table class="mt">
        <tbody>
          <tr><th>重建策略</th><td>{{ result.strategy }}</td></tr>
          <tr><th>规则基准</th><td>{{ result.ruleBasis === 'REPLAYED_OLD' ? '按封存旧规则（旧密钥代际 ' + result.keyGenerationKid + '）' : '对比用：当前新密钥代际（预期漂移）' }}</td></tr>
          <tr><th>提供方状态</th><td>{{ result.providerState || '不适用（托管路线）' }}</td></tr>
          <tr v-if="result.matchCallsUsed != null"><th>受控匹配调用</th><td>{{ result.matchCallsUsed }} / {{ result.matchCallsMax }}</td></tr>
          <tr><th>结论</th><td>{{ result.conclusion }}</td></tr>
        </tbody>
      </table>

      <div v-if="result.verdict === 'DIVERGED'" class="alert bad mt">
        <b>差异调查</b>
        <ul class="tight">
          <li>封存 {{ result.diff.sealedCount }} 例 / 重建 {{ result.diff.rebuiltCount }} 例</li>
          <li v-if="result.diff.missingInRebuild.length">重建缺失 {{ result.diff.missingInRebuild.length }} 个令牌</li>
          <li v-if="result.diff.addedInRebuild.length">重建多出 {{ result.diff.addedInRebuild.length }} 个令牌</li>
          <li v-if="result.diff.digestMismatch.length">摘要不一致 {{ result.diff.digestMismatch.length }} 个令牌</li>
          <li v-for="(c, i) in result.diff.investigationChecklist" :key="i">{{ c }}</li>
        </ul>
      </div>
      <div v-if="result.verdict === 'EVIDENCE_INCOMPLETE'" class="alert warn mt">
        <b>证据不完整（拒绝成员漂移）</b>
        <ul class="tight"><li v-for="(g, i) in result.evidenceGaps" :key="i">{{ g }}</li></ul>
        <div class="small">须先补齐用途授权/输入批次或提供方可用性，不得以替代输入重建成员。</div>
      </div>

      <div v-if="result.rebuiltDigests" class="grid cols-2 mt">
        <div><h3>封存逐项摘要（前 20）</h3>
          <pre class="json">{{ JSON.stringify((result.sealedDigests || []).slice(0, 20), null, 2) }}</pre></div>
        <div><h3>重建逐项摘要（前 20）</h3>
          <pre class="json">{{ JSON.stringify((result.rebuiltDigests || []).slice(0, 20), null, 2) }}</pre></div>
      </div>
    </div>

    <div v-if="ledger" class="card mt">
      <h3>封存包完整闭包 · {{ ledger.linkVersion.code }}</h3>
      <div class="grid cols-3">
        <div><div class="kpi">{{ ledger.linkVersion.memberCount }}</div><div class="kpi-label">封存成员数</div></div>
        <div><div class="kpi mono" style="font-size:13px">{{ ledger.linkVersion.memberDigestRoot.slice(0, 20) }}…</div>
          <div class="kpi-label">成员摘要根</div></div>
        <div><div class="kpi" style="font-size:15px">{{ ledger.linkVersion.sealingStrategy }}</div>
          <div class="kpi-label">留存路线</div></div>
      </div>

      <div class="grid cols-2 mt">
        <div>
          <h3>路线取舍依据</h3>
          <pre class="json">{{ JSON.stringify(ledger.linkVersion.strategyRationale, null, 2) }}</pre>
        </div>
        <div>
          <h3>受控匹配权限</h3>
          <pre class="json">{{ JSON.stringify(ledger.linkVersion.matchPermission, null, 2) }}</pre>
        </div>
      </div>

      <div class="grid cols-2 mt">
        <div>
          <h3>提供方输入批次</h3>
          <pre class="json">{{ JSON.stringify(ledger.providerInputBatch, null, 2) }}</pre>
        </div>
        <div>
          <h3>用途授权快照</h3>
          <pre class="json">{{ JSON.stringify(ledger.purposeAuthorization, null, 2) }}</pre>
        </div>
      </div>

      <h3 class="mt">令牌化规则 / 密钥代际 / 匹配封条</h3>
      <pre class="json">{{ JSON.stringify({ rule: ledger.tokenRuleVersion, key: ledger.keyGeneration,
        seal: ledger.matchSeal, rebuildAuth: ledger.rebuildAuthorization }, null, 2) }}</pre>

      <h3 class="mt">令牌谱系账</h3>
      <table>
        <thead><tr><th>#</th><th>事件</th><th>kid</th><th>规则</th><th>成员摘要根</th><th>操作者</th><th>时间</th></tr></thead>
        <tbody>
          <tr v-for="e in ledger.lineage" :key="e.id">
            <td>{{ e.id }}</td>
            <td><span class="badge" :class="lineageClass(e.eventType)">{{ e.eventType }}</span></td>
            <td class="mono small">{{ e.keyGenerationKid }}</td>
            <td class="mono small">{{ e.tokenRuleLabel }}</td>
            <td class="mono small">{{ (e.memberDigestRoot || '').slice(0, 16) }}…</td>
            <td class="small">{{ e.actor }}</td>
            <td class="small">{{ fmt(e.createdAt) }}</td>
          </tr>
        </tbody>
      </table>

      <h3 class="mt">重建历史</h3>
      <table>
        <thead><tr><th>run</th><th>策略</th><th>基准</th><th>结论</th><th>重建/封存</th><th>摘要根一致</th><th>时间</th></tr></thead>
        <tbody>
          <tr v-for="r in ledger.rebuildRuns" :key="r.id">
            <td>{{ r.id }}</td>
            <td class="small">{{ r.strategy }}</td>
            <td class="small">{{ r.ruleBasis }}</td>
            <td><span class="badge" :class="verdictClass(r.verdict)">{{ r.verdict }}</span></td>
            <td>{{ r.rebuiltMemberCount }} / {{ r.sealedMemberCount }}</td>
            <td>{{ r.digestRootMatch ? '✓' : '✗' }}</td>
            <td class="small">{{ fmt(r.createdAt) }}</td>
          </tr>
          <tr v-if="!ledger.rebuildRuns.length"><td colspan="7" class="muted">尚未执行受控重建</td></tr>
        </tbody>
      </table>

      <div v-if="ledger.expiryCertificate" class="mt">
        <h3>到期证明</h3>
        <pre class="json">{{ JSON.stringify(ledger.expiryCertificate, null, 2) }}</pre>
      </div>
      <div class="mt flex-between">
        <div class="small">
          <button class="btn-ghost btn-sm" @click="rebuild(ledger.linkVersion, 'CURRENT_KEY_DRIFT')"
                  :disabled="!ledger.linkVersion.windowOpen">
            对比实验：用当前新密钥重建（应漂移）
          </button>
        </div>
        <div>
          <button v-if="auth.isAdmin && ledger.linkVersion.windowOpen && ledger.linkVersion.status !== 'EXPIRED'"
                  class="btn-danger btn-sm" @click="forceExpire(ledger.linkVersion)">签发到期证明（演示强制到期）</button>
        </div>
      </div>
    </div>

    <div v-if="certificates.length" class="card mt">
      <h3>到期证明台账</h3>
      <table>
        <thead><tr><th>证明</th><th>链接版本</th><th>期限届满</th><th>签发</th><th>逐例可复现</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="c in certificates" :key="c.id">
            <td class="mono small">{{ c.code }}</td>
            <td class="mono small">{{ c.linkVersionCode }}</td>
            <td class="small">{{ fmt(c.windowEnd) }}</td>
            <td class="small">{{ fmt(c.issuedAt) }}</td>
            <td><span class="badge" :class="c.perCaseRecoverable ? 'warn' : 'ok'">
              {{ c.perCaseRecoverable ? '是' : '否（已销毁）' }}</span></td>
            <td><button class="btn-ghost btn-sm" @click="showCert(c)">查看证明</button></td>
          </tr>
        </tbody>
      </table>
      <div v-if="cert" class="mt">
        <h3>{{ cert.code }} · 到期证明</h3>
        <div class="alert warn">{{ cert.statement }}</div>
        <div class="grid cols-2">
          <div><h3>不可逆统计审计摘要</h3><pre class="json">{{ JSON.stringify(cert.irreversibleDigest, null, 2) }}</pre></div>
          <div><h3>销毁清单</h3><pre class="json">{{ JSON.stringify(cert.purgedManifest, null, 2) }}</pre></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import api from '../api'
import { useAuthStore } from '../store'

const auth = useAuthStore()
const versions = ref([])
const keys = ref([])
const providers = ref([])
const certificates = ref([])
const purposeAuths = ref([])
const sealApprovals = ref([])
const sealVersions = ref([])
const ledger = ref(null)
const result = ref(null)
const cert = ref(null)
const error = ref('')
const sealForm = reactive({
  approvalId: null,
  analysisVersionId: null,
  providerCode: '',
  strategy: 'AUTO',
  reproduceWindowMinutes: 43200
})

function fmt(ms) { return ms ? new Date(ms).toLocaleString() : '' }
function fmtShort(ms) { return ms ? new Date(ms).toISOString().slice(0, 16).replace('T', ' ') : '' }
function statusClass(s) {
  return { SEALED: 'info', REPRODUCED_OK: 'ok', DIVERGED: 'bad', EVIDENCE_INCOMPLETE: 'warn', EXPIRED: 'gray' }[s] || 'gray'
}
function verdictClass(v) {
  return { REPRODUCED_OK: 'ok', DIVERGED: 'bad', EVIDENCE_INCOMPLETE: 'warn' }[v] || 'gray'
}
function lineageClass(t) {
  return { SEAL: 'info', KEY_ROTATE: 'gray', VERIFY: 'ok', EVIDENCE_GAP: 'warn', EXPIRE: 'bad', PURGE: 'bad' }[t] || 'gray'
}

async function load() {
  const [v, k, p, c, pa, a] = await Promise.all([
    api.get('/link/versions'),
    api.get('/link/keys'),
    api.get('/link/providers'),
    api.get('/link/expiry-certificates'),
    api.get('/link/purpose-authorizations'),
    api.get('/approvals')
  ])
  versions.value = v.data
  keys.value = k.data
  providers.value = p.data
  certificates.value = c.data
  purposeAuths.value = pa.data
  // 仅允许对 ACTIVE 且未过期的批准封存新链接版本
  const now = Date.now()
  sealApprovals.value = a.data.filter((x) => x.status === 'ACTIVE' && x.validTo >= now)
  if (sealForm.approvalId == null && sealApprovals.value.length) {
    sealForm.approvalId = sealApprovals.value[0].id
    await onApprovalChange()
  }
  if (!sealForm.providerCode && providers.value.length) {
    sealForm.providerCode = providers.value[0].providerCode
  }
}

async function onApprovalChange() {
  sealForm.analysisVersionId = null
  if (!sealForm.approvalId) { sealVersions.value = []; return }
  const { data } = await api.get(`/approvals/${sealForm.approvalId}/versions`)
  sealVersions.value = data
}

async function sealVersion() {
  error.value = ''
  try {
    const { data } = await api.post('/link/versions', { ...sealForm })
    result.value = null
    await load()
    await openLedger(data)
  } catch (e) { error.value = e.response?.data?.error || e.message }
}

async function setPurposeAuth(pa, missing) {
  error.value = ''
  try {
    await api.post(`/admin/link/purpose-authorizations/${pa.id}/status`, { missing })
    await load()
  } catch (e) { error.value = e.response?.data?.error || e.message }
}

async function rotateKey() {
  error.value = ''
  try {
    await api.post('/admin/link/keys/rotate', { note: '界面触发轮换' })
    await load()
  } catch (e) { error.value = e.response?.data?.error || e.message }
}

async function toggleProvider(p) {
  error.value = ''
  try {
    await api.post(`/admin/link/providers/${p.providerCode}/status`, {
      status: p.status === 'AVAILABLE' ? 'UNAVAILABLE' : 'AVAILABLE'
    })
    await load()
  } catch (e) { error.value = e.response?.data?.error || e.message }
}

async function rebuild(v, ruleBasis) {
  error.value = ''
  try {
    const { data } = await api.post(`/link/versions/${v.id}/rebuild`, { ruleBasis })
    result.value = data
    await load()
    await openLedger(v)
  } catch (e) {
    error.value = e.response?.data?.error || e.message
  }
}

async function openLedger(v) {
  const { data } = await api.get(`/link/versions/${v.id}/ledger`)
  ledger.value = data
}

async function forceExpire(v) {
  if (!confirm(`确认对 ${v.code} 立即到期？将销毁逐例恢复能力，仅保留不可逆统计摘要。`)) return
  error.value = ''
  try {
    await api.post(`/admin/link/versions/${v.id}/force-expire`, { reason: '界面演示强制到期' })
    await load()
    await openLedger(v)
  } catch (e) { error.value = e.response?.data?.error || e.message }
}

function showCert(c) { cert.value = c }

onMounted(load)
</script>
