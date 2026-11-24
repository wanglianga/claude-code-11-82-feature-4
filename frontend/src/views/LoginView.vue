<template>
  <div class="login-wrap">
    <div class="login-card">
      <h1>区域影像受控分析沙箱</h1>
      <div class="sub">联合推断审查 · 累计披露预算 · 交付冻结与复现</div>
      <label>用户名</label>
      <input v-model="username" @keyup.enter="login" placeholder="admin / analyst" />
      <label>密码</label>
      <input v-model="password" type="password" @keyup.enter="login" placeholder="请输入密码" />
      <button class="btn-primary" style="width: 100%; margin-top: 20px" :disabled="loading" @click="login">
        {{ loading ? '登录中…' : '登录' }}
      </button>
      <div v-if="error" class="alert bad">{{ error }}</div>
      <div class="hint">
        测试账号：<br />
        管理员 <span class="code-inline">admin / admin123</span>（批准编译、撤回、预算治理）<br />
        分析师 <span class="code-inline">analyst / analyst123</span>（队列、查询、交付、复现）
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { useAuthStore } from '../store'

const username = ref('analyst')
const password = ref('analyst123')
const error = ref('')
const loading = ref(false)
const router = useRouter()
const auth = useAuthStore()

async function login() {
  error.value = ''
  loading.value = true
  try {
    const { data } = await api.post('/auth/login', {
      username: username.value,
      password: password.value
    })
    auth.setSession(data.token, data.user)
    router.push('/scenarios')
  } catch (e) {
    error.value = e.response?.data?.error || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>
