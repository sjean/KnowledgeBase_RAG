<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { me } from '../api/auth'
import { askQuestion } from '../api/chat'
import { deleteDocument, fetchDocuments, retryDocument, uploadFile } from '../api/file'

const router = useRouter()
const state = reactive({
  user: null,
  question: '',
  uploading: false,
  chatting: false,
  actioningDocumentIds: {}
})
const documents = ref([])
let pollTimer = null

const messages = ref([
  {
    role: 'assistant',
    answer: '欢迎使用企业AI知识库系统。上传 PDF / Word / TXT 后，你可以开始提问。',
    sources: [],
    toolUsed: null
  }
])

async function loadUser() {
  try {
    const { data } = await me()
    state.user = data
  } catch (error) {
    logout()
  }
}

async function loadDocuments() {
  try {
    const { data } = await fetchDocuments()
    documents.value = data
  } catch (error) {
    console.error(error)
  }
}

async function handleAsk() {
  if (!state.question.trim()) {
    return
  }
  const question = state.question
  messages.value.push({
    role: 'user',
    answer: question,
    sources: [],
    toolUsed: null
  })
  state.question = ''
  state.chatting = true
  try {
    const { data } = await askQuestion(question)
    messages.value.push({
      role: 'assistant',
      answer: data.answer,
      sources: data.sources || [],
      toolUsed: data.toolUsed
    })
  } catch (error) {
    messages.value.push({
      role: 'assistant',
      answer: error.response?.data?.message || '提问失败，请检查后端日志。',
      sources: [],
      toolUsed: null
    })
  } finally {
    state.chatting = false
  }
}

async function handleFileChange(event) {
  const file = event.target.files?.[0]
  if (!file) {
    return
  }
  state.uploading = true
  try {
    const { data } = await uploadFile(file)
    await loadDocuments()
    messages.value.push({
      role: 'assistant',
      answer: `文档已加入异步处理队列：${data.fileName}，当前状态 ${data.status}。`,
      sources: [],
      toolUsed: null
    })
  } catch (error) {
    messages.value.push({
      role: 'assistant',
      answer: error.response?.data?.message || '文件上传失败。',
      sources: [],
      toolUsed: null
    })
  } finally {
    state.uploading = false
    event.target.value = ''
  }
}

function isDocumentActioning(documentId) {
  return Boolean(state.actioningDocumentIds[documentId])
}

async function handleRetry(doc) {
  if (isDocumentActioning(doc.documentId) || doc.status !== 'FAILED') {
    return
  }
  state.actioningDocumentIds[doc.documentId] = true
  try {
    await retryDocument(doc.documentId)
    await loadDocuments()
    messages.value.push({
      role: 'assistant',
      answer: `已重新加入处理队列：${doc.fileName}。`,
      sources: [],
      toolUsed: null
    })
  } catch (error) {
    messages.value.push({
      role: 'assistant',
      answer: error.response?.data?.message || '重试失败，请稍后再试。',
      sources: [],
      toolUsed: null
    })
  } finally {
    delete state.actioningDocumentIds[doc.documentId]
  }
}

async function handleDelete(doc) {
  if (isDocumentActioning(doc.documentId)) {
    return
  }
  const confirmed = window.confirm(`确认删除文档《${doc.fileName}》吗？`)
  if (!confirmed) {
    return
  }
  state.actioningDocumentIds[doc.documentId] = true
  try {
    await deleteDocument(doc.documentId)
    await loadDocuments()
    messages.value.push({
      role: 'assistant',
      answer: `文档已删除：${doc.fileName}。`,
      sources: [],
      toolUsed: null
    })
  } catch (error) {
    messages.value.push({
      role: 'assistant',
      answer: error.response?.data?.message || '删除失败，请稍后再试。',
      sources: [],
      toolUsed: null
    })
  } finally {
    delete state.actioningDocumentIds[doc.documentId]
  }
}

function logout() {
  localStorage.removeItem('token')
  router.push('/login')
}

function statusText(status) {
  const mapping = {
    UPLOADED: '已上传',
    PARSING: '解析中',
    EMBEDDING: '向量化中',
    READY: '可提问',
    FAILED: '处理失败'
  }
  return mapping[status] || status
}

onMounted(async () => {
  await loadUser()
  await loadDocuments()
  pollTimer = window.setInterval(loadDocuments, 4000)
})

onUnmounted(() => {
  if (pollTimer) {
    window.clearInterval(pollTimer)
  }
})
</script>

<template>
  <div class="chat-page">
    <aside class="sidebar">
      <div>
        <p class="mini">AI Knowledge Base</p>
        <h1>企业知识助手</h1>
      </div>

      <div class="panel">
        <p class="label">当前用户</p>
        <p>{{ state.user?.username || '加载中...' }}</p>
        <p class="muted">userId: {{ state.user?.userId || '-' }}</p>
        <p class="muted">role: {{ state.user?.role || '-' }}</p>
      </div>

      <label class="upload">
        <input type="file" accept=".pdf,.doc,.docx,.txt" hidden @change="handleFileChange" />
        {{ state.uploading ? '上传中...' : '上传知识文档' }}
      </label>

      <div class="panel docs-panel">
        <p class="label">文档状态</p>
        <div v-if="documents.length === 0" class="muted">还没有上传文档</div>
        <div v-for="doc in documents" :key="doc.documentId" class="doc-item">
          <div class="doc-top">
            <strong>{{ doc.fileName }}</strong>
            <span class="status" :class="doc.status.toLowerCase()">{{ statusText(doc.status) }}</span>
          </div>
          <div class="progress-track">
            <div class="progress-bar" :style="{ width: `${doc.progress}%` }" />
          </div>
          <div class="doc-meta">
            <span>{{ doc.progress }}%</span>
            <span v-if="doc.chunkCount">chunks: {{ doc.chunkCount }}</span>
          </div>
          <div class="doc-actions">
            <button
              class="doc-action secondary"
              :disabled="isDocumentActioning(doc.documentId) || ['PARSING', 'EMBEDDING'].includes(doc.status)"
              @click="handleDelete(doc)"
            >
              {{ isDocumentActioning(doc.documentId) ? '处理中...' : '删除' }}
            </button>
            <button
              class="doc-action primary"
              :disabled="isDocumentActioning(doc.documentId) || doc.status !== 'FAILED'"
              @click="handleRetry(doc)"
            >
              重试
            </button>
          </div>
          <div v-if="doc.errorMessage" class="doc-error">{{ doc.errorMessage }}</div>
        </div>
      </div>

      <button class="logout" @click="logout">退出登录</button>
    </aside>

    <main class="main">
      <div class="messages">
        <div
          v-for="(message, index) in messages"
          :key="index"
          class="message"
          :class="message.role"
        >
          <div class="bubble">
            <div class="role">{{ message.role === 'user' ? '你' : 'AI助手' }}</div>
            <div class="content">{{ message.answer }}</div>
            <div v-if="message.toolUsed" class="tool">Tool: {{ message.toolUsed }}</div>
            <div v-if="message.sources?.length" class="sources">
              <div class="source-title">Sources</div>
              <div v-for="source in message.sources" :key="source.chunkId" class="source-item">
                <strong>{{ source.fileName }}</strong>
                <span>{{ source.content }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="composer">
        <textarea
          v-model="state.question"
          placeholder="请输入问题，例如：总结我上传的制度文档重点，或者查询当前文档数量。"
          @keyup.ctrl.enter="handleAsk"
        />
        <button :disabled="state.chatting" @click="handleAsk">
          {{ state.chatting ? '处理中...' : '发送' }}
        </button>
      </div>
    </main>
  </div>
</template>

<style scoped>
.chat-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 320px 1fr;
}

.sidebar {
  padding: 28px;
  background: rgba(255, 255, 255, 0.55);
  border-right: 1px solid var(--line);
  backdrop-filter: blur(14px);
}

.mini {
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--accent);
}

.sidebar h1 {
  margin: 10px 0 28px;
  font-size: 36px;
  line-height: 1.1;
}

.panel {
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid var(--line);
  margin-bottom: 16px;
}

.label,
.muted {
  color: var(--muted);
}

.docs-panel {
  max-height: 320px;
  overflow: auto;
}

.doc-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}

.doc-item:last-child {
  border-bottom: none;
}

.doc-top,
.doc-meta {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.doc-top strong {
  max-width: 170px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.08);
}

.status.ready {
  background: rgba(22, 163, 74, 0.14);
  color: #166534;
}

.status.failed {
  background: rgba(220, 38, 38, 0.14);
  color: #b91c1c;
}

.status.embedding,
.status.parsing,
.status.uploaded {
  background: rgba(29, 78, 216, 0.12);
  color: #1d4ed8;
}

.progress-track {
  margin: 8px 0 6px;
  width: 100%;
  height: 8px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.2);
  overflow: hidden;
}

.progress-bar {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--accent), var(--accent-2));
}

.doc-meta {
  font-size: 12px;
  color: var(--muted);
}

.doc-error {
  margin-top: 6px;
  color: var(--danger);
  font-size: 12px;
  line-height: 1.5;
}

.doc-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.doc-action {
  flex: 1;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 8px 10px;
  background: rgba(255, 255, 255, 0.9);
  cursor: pointer;
}

.doc-action.primary {
  background: rgba(29, 78, 216, 0.1);
  color: #1d4ed8;
}

.doc-action.secondary {
  color: var(--danger);
}

.doc-action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.upload,
.logout,
.composer button {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  border: none;
  border-radius: 16px;
  padding: 14px 16px;
  cursor: pointer;
}

.upload {
  background: linear-gradient(120deg, rgba(15, 118, 110, 0.14), rgba(29, 78, 216, 0.14));
  border: 1px dashed rgba(15, 118, 110, 0.3);
  margin-bottom: 12px;
}

.logout {
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--line);
}

.main {
  display: grid;
  grid-template-rows: 1fr auto;
  padding: 24px;
  gap: 20px;
}

.messages {
  overflow: auto;
  padding-right: 4px;
}

.message {
  display: flex;
  margin-bottom: 16px;
}

.message.user {
  justify-content: flex-end;
}

.bubble {
  max-width: min(760px, 90%);
  padding: 18px 20px;
  border-radius: 22px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.84);
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.06);
}

.message.user .bubble {
  background: linear-gradient(120deg, rgba(15, 118, 110, 0.92), rgba(29, 78, 216, 0.92));
  color: white;
}

.role {
  font-size: 12px;
  opacity: 0.7;
  margin-bottom: 8px;
}

.content {
  white-space: pre-wrap;
  line-height: 1.7;
}

.tool {
  margin-top: 12px;
  font-size: 13px;
  color: var(--accent);
}

.sources {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.source-title {
  margin-bottom: 8px;
  color: var(--muted);
}

.source-item {
  margin-bottom: 10px;
  display: grid;
  gap: 4px;
}

.source-item span {
  color: var(--muted);
  font-size: 14px;
  line-height: 1.5;
}

.composer {
  display: grid;
  grid-template-columns: 1fr 140px;
  gap: 14px;
}

textarea {
  min-height: 100px;
  resize: vertical;
  padding: 16px;
  border-radius: 20px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.86);
}

.composer button {
  background: linear-gradient(90deg, var(--accent), var(--accent-2));
  color: white;
}

@media (max-width: 960px) {
  .chat-page {
    grid-template-columns: 1fr;
  }

  .composer {
    grid-template-columns: 1fr;
  }
}
</style>
