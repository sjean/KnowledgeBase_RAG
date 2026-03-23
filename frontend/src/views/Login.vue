<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api/auth'

const router = useRouter()
const form = reactive({
  username: 'user',
  password: 'user123'
})
const loading = ref(false)
const error = ref('')

async function submit() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await login(form)
    localStorage.setItem('token', data.token)
    router.push('/chat')
  } catch (err) {
    error.value = err.response?.data?.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="hero">
      <p class="tag">Enterprise AI Knowledge Base</p>
      <h1>企业级AI知识库系统</h1>
      <p class="desc">
        集成 JWT + RBAC、RAG 检索增强生成、Tool Calling Agent 与多用户知识隔离。
      </p>
    </div>

    <div class="card">
      <h2>登录系统</h2>
      <p class="hint">默认账号：admin/admin123 或 user/user123</p>
      <input v-model="form.username" placeholder="用户名" />
      <input v-model="form.password" type="password" placeholder="密码" @keyup.enter="submit" />
      <button :disabled="loading" @click="submit">
        {{ loading ? '登录中...' : '登录' }}
      </button>
      <p v-if="error" class="error">{{ error }}</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  align-items: center;
  gap: 32px;
  padding: 48px;
}

.hero {
  padding: 32px;
}

.tag {
  display: inline-block;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(15, 118, 110, 0.12);
  color: var(--accent);
  letter-spacing: 0.08em;
  text-transform: uppercase;
  font-size: 12px;
}

h1 {
  margin: 16px 0 12px;
  font-size: clamp(36px, 5vw, 64px);
  line-height: 1.05;
}

.desc {
  max-width: 560px;
  color: var(--muted);
  font-size: 18px;
  line-height: 1.8;
}

.card {
  width: min(420px, 100%);
  justify-self: center;
  background: var(--panel);
  backdrop-filter: blur(18px);
  border: 1px solid var(--line);
  border-radius: 24px;
  padding: 32px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.12);
}

.card h2 {
  margin: 0 0 8px;
}

.hint {
  margin: 0 0 20px;
  color: var(--muted);
}

input {
  width: 100%;
  margin-bottom: 14px;
  padding: 14px 16px;
  border-radius: 14px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.75);
}

button {
  width: 100%;
  padding: 14px 16px;
  border: none;
  border-radius: 14px;
  background: linear-gradient(90deg, var(--accent), var(--accent-2));
  color: white;
  cursor: pointer;
}

.error {
  color: var(--danger);
}

@media (max-width: 900px) {
  .login-page {
    grid-template-columns: 1fr;
    padding: 24px;
  }

  .hero {
    padding: 0;
  }
}
</style>
