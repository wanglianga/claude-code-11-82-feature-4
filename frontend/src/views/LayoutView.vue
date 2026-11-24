<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="logo">影像受控<br />分析沙箱</div>
      <nav>
        <RouterLink to="/scenarios">场景验证导览</RouterLink>
        <RouterLink to="/approvals">批准与分析版本</RouterLink>
        <RouterLink to="/cohorts">队列复现</RouterLink>
        <RouterLink to="/query">交互查询审查</RouterLink>
        <RouterLink to="/table-sets">固定表组核验</RouterLink>
        <RouterLink to="/deliverables">交付与复现台账</RouterLink>
        <RouterLink to="/link-versions">链接版本封存与重建</RouterLink>
        <RouterLink to="/budget">预算与约束图</RouterLink>
        <RouterLink to="/audit">审计事件</RouterLink>
      </nav>
      <div class="who">
        {{ auth.user?.displayName }}（{{ auth.user?.role }}）
        <div style="margin-top: 8px">
          <button class="btn-ghost btn-sm" @click="logout">退出登录</button>
        </div>
      </div>
    </aside>
    <main class="main">
      <RouterView />
    </main>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store'

const auth = useAuthStore()
const router = useRouter()
function logout() {
  auth.logout()
  router.push('/login')
}
</script>
