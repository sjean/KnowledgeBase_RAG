<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { me } from '../api/auth'
import { askQuestion, fetchChatSessionDetail, fetchChatSessions } from '../api/chat'
import { buildDocumentStreamUrl, deleteDocument, fetchDocumentsPage, retryDocument, uploadFile } from '../api/file'

const router = useRouter()

function createWelcomeMessage() {
  return {
    messageId: 'welcome',
    role: 'ASSISTANT',
    content: '欢迎使用企业AI知识库系统。你现在可以保存会话历史、继续多轮提问，并实时看到文档处理状态。',
    sources: [],
    toolUsed: null,
    createdAt: null
  }
}

const state = reactive({
  user: null,
  question: '',
  uploading: false,
  chatting: false,
  sessionLoading: false,
  docsLoading: false,
  currentSessionId: null,
  sessionKeyword: '',
  sessionPage: 0,
  sessionSize: 8,
  docKeyword: '',
  docPage: 0,
  docSize: 6,
  actioningDocumentIds: {},
  expandedErrors: {}
})

const sessions = ref([])
const sessionMeta = reactive({
  totalElements: 0,
  totalPages: 0
})
const documents = ref([])
const documentMeta = reactive({
  totalElements: 0,
  totalPages: 0
})
const messages = ref([createWelcomeMessage()])
let documentStream = null

async function loadUser() {
  try {
    const { data } = await me()
    state.user = data
  } catch (error) {
    logout()
  }
}

async function loadSessions({ ensureSelection = false } = {}) {
  const { data } = await fetchChatSessions({
    keyword: state.sessionKeyword,
    page: state.sessionPage,
    size: state.sessionSize
  })
  sessions.value = data.items || []
  sessionMeta.totalElements = data.totalElements || 0
  sessionMeta.totalPages = data.totalPages || 0

  if (sessionMeta.totalPages > 0 && state.sessionPage >= sessionMeta.totalPages) {
    state.sessionPage = sessionMeta.totalPages - 1
    return loadSessions({ ensureSelection })
  }

  if (ensureSelection && !state.currentSessionId && sessions.value.length > 0) {
    await openSession(sessions.value[0].sessionId)
  }
}

async function loadDocuments() {
  state.docsLoading = true
  try {
    const { data } = await fetchDocumentsPage({
      keyword: state.docKeyword,
      page: state.docPage,
      size: state.docSize
    })
    documents.value = data.items || []
    documentMeta.totalElements = data.totalElements || 0
    documentMeta.totalPages = data.totalPages || 0

    if (documentMeta.totalPages > 0 && state.docPage >= documentMeta.totalPages) {
      state.docPage = documentMeta.totalPages - 1
      return loadDocuments()
    }
  } finally {
    state.docsLoading = false
  }
}

async function openSession(sessionId) {
  state.sessionLoading = true
  try {
    const { data } = await fetchChatSessionDetail(sessionId)
    state.currentSessionId = data.sessionId
    messages.value = (data.messages || []).length > 0 ? data.messages : [createWelcomeMessage()]
  } catch (error) {
    messages.value = [
      {
        messageId: `error-${Date.now()}`,
        role: 'ASSISTANT',
        content: error.response?.data?.message || '加载会话失败。',
        sources: [],
        toolUsed: null,
        createdAt: null
      }
    ]
  } finally {
    state.sessionLoading = false
  }
}

function startNewSession() {
  state.currentSessionId = null
  state.question = ''
  messages.value = [createWelcomeMessage()]
}

async function handleAsk() {
  if (!state.question.trim()) {
    return
  }
  const question = state.question.trim()
  state.question = ''
  messages.value.push({
    messageId: `local-user-${Date.now()}`,
    role: 'USER',
    content: question,
    sources: [],
    toolUsed: null,
    createdAt: new Date().toISOString()
  })
  state.chatting = true
  try {
    const { data } = await askQuestion(question, state.currentSessionId)
    state.currentSessionId = data.sessionId
    await Promise.all([
      openSession(data.sessionId),
      loadSessions()
    ])
  } catch (error) {
    messages.value.push({
      messageId: `local-error-${Date.now()}`,
      role: 'ASSISTANT',
      content: error.response?.data?.message || '提问失败，请检查后端日志。',
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
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
      messageId: `upload-${Date.now()}`,
      role: 'ASSISTANT',
      content: `文档已加入异步处理队列：${data.fileName}，当前状态 ${statusText(data.status)}。`,
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
    })
  } catch (error) {
    messages.value.push({
      messageId: `upload-error-${Date.now()}`,
      role: 'ASSISTANT',
      content: error.response?.data?.message || '文件上传失败。',
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
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
      messageId: `retry-${Date.now()}`,
      role: 'ASSISTANT',
      content: `已重新加入处理队列：${doc.fileName}。`,
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
    })
  } catch (error) {
    messages.value.push({
      messageId: `retry-error-${Date.now()}`,
      role: 'ASSISTANT',
      content: error.response?.data?.message || '重试失败，请稍后再试。',
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
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
      messageId: `delete-${Date.now()}`,
      role: 'ASSISTANT',
      content: `文档已删除：${doc.fileName}。`,
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
    })
  } catch (error) {
    messages.value.push({
      messageId: `delete-error-${Date.now()}`,
      role: 'ASSISTANT',
      content: error.response?.data?.message || '删除失败，请稍后再试。',
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
    })
  } finally {
    delete state.actioningDocumentIds[doc.documentId]
  }
}

function toggleError(docId) {
  state.expandedErrors[docId] = !state.expandedErrors[docId]
}

function connectDocumentStream() {
  if (documentStream) {
    documentStream.close()
  }
  documentStream = new EventSource(buildDocumentStreamUrl())
  documentStream.addEventListener('document-change', async () => {
    await loadDocuments()
  })
  documentStream.onerror = () => {
    if (!localStorage.getItem('token') && documentStream) {
      documentStream.close()
      documentStream = null
    }
  }
}

function applySessionSearch() {
  state.sessionPage = 0
  loadSessions()
}

function applyDocumentSearch() {
  state.docPage = 0
  loadDocuments()
}

function previousSessionPage() {
  if (state.sessionPage === 0) {
    return
  }
  state.sessionPage -= 1
  loadSessions()
}

function nextSessionPage() {
  if (state.sessionPage + 1 >= sessionMeta.totalPages) {
    return
  }
  state.sessionPage += 1
  loadSessions()
}

function previousDocumentPage() {
  if (state.docPage === 0) {
    return
  }
  state.docPage -= 1
  loadDocuments()
}

function nextDocumentPage() {
  if (state.docPage + 1 >= documentMeta.totalPages) {
    return
  }
  state.docPage += 1
  loadDocuments()
}

function logout() {
  localStorage.removeItem('token')
  if (documentStream) {
    documentStream.close()
    documentStream = null
  }
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

function formatTime(value) {
  if (!value) {
    return ''
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value))
}

function isActiveSession(sessionId) {
  return state.currentSessionId === sessionId
}

onMounted(async () => {
  await loadUser()
  await Promise.all([
    loadSessions({ ensureSelection: true }),
    loadDocuments()
  ])
  connectDocumentStream()
})

onUnmounted(() => {
  if (documentStream) {
    documentStream.close()
    documentStream = null
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

      <div class="panel">
        <div class="section-header">
          <p class="label">会话历史</p>
          <button class="tiny-btn" @click="startNewSession">新建</button>
        </div>
        <div class="toolbar">
          <input v-model.trim="state.sessionKeyword" class="search-input" placeholder="搜索会话标题" @keyup.enter="applySessionSearch" />
          <button class="tiny-btn" @click="applySessionSearch">搜索</button>
        </div>
        <div class="session-list">
          <button
            v-for="session in sessions"
            :key="session.sessionId"
            class="session-item"
            :class="{ active: isActiveSession(session.sessionId) }"
            @click="openSession(session.sessionId)"
          >
            <strong>{{ session.title }}</strong>
            <span>{{ formatTime(session.updatedAt) }}</span>
          </button>
          <div v-if="sessions.length === 0" class="muted">还没有历史会话</div>
        </div>
        <div class="pager">
          <button class="tiny-btn" :disabled="state.sessionPage === 0" @click="previousSessionPage">上一页</button>
          <span>{{ state.sessionPage + 1 }} / {{ sessionMeta.totalPages || 1 }}</span>
          <button class="tiny-btn" :disabled="state.sessionPage + 1 >= sessionMeta.totalPages" @click="nextSessionPage">下一页</button>
        </div>
      </div>

      <label class="upload">
        <input type="file" accept=".pdf,.doc,.docx,.txt" hidden @change="handleFileChange" />
        {{ state.uploading ? '上传中...' : '上传知识文档' }}
      </label>

      <div class="panel docs-panel">
        <div class="section-header">
          <p class="label">文档管理</p>
          <button class="tiny-btn" :disabled="state.docsLoading" @click="loadDocuments">
            {{ state.docsLoading ? '刷新中' : '刷新' }}
          </button>
        </div>
        <div class="toolbar">
          <input v-model.trim="state.docKeyword" class="search-input" placeholder="搜索文件名或用户" @keyup.enter="applyDocumentSearch" />
          <button class="tiny-btn" @click="applyDocumentSearch">搜索</button>
        </div>
        <div v-if="documents.length === 0" class="muted">当前没有匹配文档</div>
        <div v-for="doc in documents" :key="doc.documentId" class="doc-item">
          <div class="doc-top">
            <strong>{{ doc.fileName }}</strong>
            <span class="status" :class="doc.status.toLowerCase()">{{ statusText(doc.status) }}</span>
          </div>
          <div v-if="state.user?.role === 'ADMIN'" class="doc-owner">
            owner: {{ doc.ownerUsername || `user-${doc.ownerUserId}` }}
          </div>
          <div class="progress-track">
            <div class="progress-bar" :style="{ width: `${doc.progress}%` }" />
          </div>
          <div class="doc-meta">
            <span>{{ doc.progress }}%</span>
            <span v-if="doc.chunkCount">chunks: {{ doc.chunkCount }}</span>
            <span>{{ formatTime(doc.updatedAt) }}</span>
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
          <div v-if="doc.errorMessage" class="error-box">
            <button class="error-toggle" @click="toggleError(doc.documentId)">
              {{ state.expandedErrors[doc.documentId] ? '收起失败原因' : '查看失败原因详情' }}
            </button>
            <div v-if="state.expandedErrors[doc.documentId]" class="doc-error">{{ doc.errorMessage }}</div>
          </div>
        </div>
        <div class="pager">
          <button class="tiny-btn" :disabled="state.docPage === 0" @click="previousDocumentPage">上一页</button>
          <span>{{ state.docPage + 1 }} / {{ documentMeta.totalPages || 1 }}</span>
          <button class="tiny-btn" :disabled="state.docPage + 1 >= documentMeta.totalPages" @click="nextDocumentPage">下一页</button>
        </div>
      </div>

      <button class="logout" @click="logout">退出登录</button>
    </aside>

    <main class="main">
      <div class="chat-header">
        <div>
          <p class="label">当前会话</p>
          <h2>{{ state.currentSessionId ? `会话 #${state.currentSessionId}` : '新会话' }}</h2>
        </div>
        <div class="muted">{{ state.sessionLoading ? '会话加载中...' : '会话内容自动保存' }}</div>
      </div>

      <div class="messages">
        <div
          v-for="message in messages"
          :key="message.messageId"
          class="message"
          :class="message.role === 'USER' ? 'user' : 'assistant'"
        >
          <div class="bubble">
            <div class="role">{{ message.role === 'USER' ? '你' : 'AI助手' }}</div>
            <div class="content">{{ message.content }}</div>
            <div v-if="message.toolUsed" class="tool">Tool: {{ message.toolUsed }}</div>
            <div v-if="message.sources?.length" class="sources">
              <div class="source-title">Sources</div>
              <div v-for="source in message.sources" :key="source.chunkId" class="source-item">
                <strong>{{ source.fileName }}</strong>
                <span>{{ source.content }}</span>
              </div>
            </div>
            <div v-if="message.createdAt" class="time">{{ formatTime(message.createdAt) }}</div>
          </div>
        </div>
      </div>

      <div class="composer">
        <textarea
          v-model="state.question"
          placeholder="请输入问题，例如：继续总结上一个制度文档，并结合刚才的答案补充风险点。"
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
  grid-template-columns: 360px 1fr;
}

.sidebar {
  padding: 24px;
  background: rgba(255, 255, 255, 0.55);
  border-right: 1px solid var(--line);
  backdrop-filter: blur(14px);
  overflow-y: auto;
}

.mini {
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--accent);
}

.sidebar h1 {
  margin: 10px 0 20px;
  font-size: 34px;
  line-height: 1.1;
}

.panel {
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.74);
  border: 1px solid var(--line);
  margin-bottom: 16px;
}

.section-header,
.toolbar,
.doc-top,
.doc-meta,
.pager,
.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.toolbar {
  margin-top: 10px;
}

.label,
.muted,
.doc-owner,
.time {
  color: var(--muted);
}

.search-input,
textarea {
  width: 100%;
  border-radius: 14px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.9);
  padding: 12px 14px;
}

.tiny-btn,
.doc-action,
.upload,
.logout,
.composer button,
.session-item {
  border: none;
  border-radius: 14px;
  cursor: pointer;
}

.tiny-btn {
  padding: 8px 12px;
  background: rgba(15, 23, 42, 0.07);
}

.session-list {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.session-item {
  text-align: left;
  padding: 12px 14px;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid transparent;
  display: grid;
  gap: 4px;
}

.session-item.active {
  border-color: rgba(29, 78, 216, 0.28);
  background: rgba(29, 78, 216, 0.08);
}

.session-item span {
  font-size: 12px;
  color: var(--muted);
}

.upload,
.logout,
.composer button {
  width: 100%;
  display: inline-flex;
  justify-content: center;
  align-items: center;
  padding: 14px 16px;
}

.upload {
  background: linear-gradient(120deg, rgba(15, 118, 110, 0.14), rgba(29, 78, 216, 0.14));
  border: 1px dashed rgba(15, 118, 110, 0.3);
  margin-bottom: 12px;
}

.docs-panel {
  max-height: 42vh;
  overflow: auto;
}

.doc-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}

.doc-item:last-child {
  border-bottom: none;
}

.doc-top strong {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-owner,
.doc-meta,
.time {
  font-size: 12px;
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

.doc-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.doc-action {
  flex: 1;
  padding: 8px 10px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.9);
}

.doc-action.primary {
  background: rgba(29, 78, 216, 0.1);
  color: #1d4ed8;
}

.doc-action.secondary {
  color: var(--danger);
}

.doc-action:disabled,
.tiny-btn:disabled,
.composer button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.error-box {
  margin-top: 8px;
}

.error-toggle {
  border: none;
  background: transparent;
  padding: 0;
  color: var(--danger);
  cursor: pointer;
}

.doc-error {
  margin-top: 6px;
  color: var(--danger);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.logout {
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--line);
  margin-top: 12px;
}

.main {
  display: grid;
  grid-template-rows: auto 1fr auto;
  padding: 24px;
  gap: 18px;
}

.chat-header h2 {
  margin: 6px 0 0;
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
  max-width: min(760px, 92%);
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
  min-height: 110px;
  resize: vertical;
}

.composer button {
  background: linear-gradient(90deg, var(--accent), var(--accent-2));
  color: white;
}

@media (max-width: 1120px) {
  .chat-page {
    grid-template-columns: 1fr;
  }

  .docs-panel {
    max-height: none;
  }
}

@media (max-width: 720px) {
  .composer {
    grid-template-columns: 1fr;
  }

  .toolbar,
  .section-header,
  .doc-top,
  .doc-meta,
  .pager,
  .chat-header {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
