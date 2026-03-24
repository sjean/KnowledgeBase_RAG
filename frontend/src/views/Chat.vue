<script setup>
import { nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { me } from '../api/auth'
import { deleteChatSession, fetchChatSessionDetail, fetchChatSessions, renameChatSession, streamQuestion } from '../api/chat'
import { buildDocumentStreamUrl, deleteDocument, fetchDocumentsPage, retryDocument, uploadFile } from '../api/file'
import { renderMarkdown } from '../utils/markdown'

const router = useRouter()
const messagesContainer = ref(null)

function createWelcomeMessage() {
  return buildMessage({
    messageId: 'welcome',
    role: 'ASSISTANT',
    content: '欢迎使用企业AI知识库系统。你现在可以保存会话历史、继续多轮提问，并实时看到文档处理状态。',
    sources: [],
    toolUsed: null,
    createdAt: null
  })
}

const state = reactive({
  user: null,
  question: '',
  uploading: false,
  chatting: false,
  sessionLoading: false,
  docsLoading: false,
  currentSessionId: null,
  currentSessionTitle: '新会话',
  sessionKeyword: '',
  sessionPage: 0,
  sessionSize: 8,
  actioningSessionIds: {},
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
let chatAbortController = null
let scrollFramePending = false
const streamingState = {
  queue: [],
  timer: null,
  activeMessage: null
}

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
  syncCurrentSessionTitle()

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
    state.currentSessionTitle = data.title || `会话 #${data.sessionId}`
    messages.value = (data.messages || []).length > 0
      ? data.messages.map((message) => buildMessage(message))
      : [createWelcomeMessage()]
    await scrollToBottom(true)
  } catch (error) {
    messages.value = [
      buildMessage({
        messageId: `error-${Date.now()}`,
        role: 'ASSISTANT',
        content: error.response?.data?.message || '加载会话失败。',
        sources: [],
        toolUsed: null,
        createdAt: null
      })
    ]
  } finally {
    state.sessionLoading = false
  }
}

function isSessionActioning(sessionId) {
  return Boolean(state.actioningSessionIds[sessionId])
}

function startNewSession() {
  state.currentSessionId = null
  state.currentSessionTitle = '新会话'
  state.question = ''
  messages.value = [createWelcomeMessage()]
}

async function handleRenameSession(session) {
  if (isSessionActioning(session.sessionId)) {
    return
  }
  const nextTitle = window.prompt('请输入新的会话标题', session.title)
  if (nextTitle === null) {
    return
  }
  const normalizedTitle = nextTitle.trim()
  if (!normalizedTitle) {
    window.alert('会话标题不能为空')
    return
  }

  state.actioningSessionIds[session.sessionId] = true
  try {
    await renameChatSession(session.sessionId, normalizedTitle)
    if (state.currentSessionId === session.sessionId) {
      state.currentSessionTitle = normalizedTitle
    }
    await loadSessions()
  } catch (error) {
    window.alert(error.response?.data?.message || '重命名失败，请稍后再试。')
  } finally {
    delete state.actioningSessionIds[session.sessionId]
  }
}

async function handleDeleteSession(session) {
  if (isSessionActioning(session.sessionId)) {
    return
  }
  const confirmed = window.confirm(`确认删除会话《${session.title}》吗？`)
  if (!confirmed) {
    return
  }

  state.actioningSessionIds[session.sessionId] = true
  try {
    await deleteChatSession(session.sessionId)
    if (state.currentSessionId === session.sessionId) {
      startNewSession()
    }
    await loadSessions({ ensureSelection: state.currentSessionId == null })
  } catch (error) {
    window.alert(error.response?.data?.message || '删除会话失败，请稍后再试。')
  } finally {
    delete state.actioningSessionIds[session.sessionId]
  }
}

async function handleAsk() {
  if (!state.question.trim() || state.chatting) {
    return
  }
  const question = state.question.trim()
  state.question = ''
  const userMessage = buildMessage({
    messageId: `local-user-${Date.now()}`,
    role: 'USER',
    content: question,
    sources: [],
    toolUsed: null,
    createdAt: new Date().toISOString()
  })
  const assistantMessage = reactive({
    messageId: `local-assistant-${Date.now()}`,
    role: 'ASSISTANT',
    content: '',
    renderedContent: '',
    sources: [],
    toolUsed: null,
    createdAt: null,
    streaming: true
  })
  messages.value.push(userMessage, assistantMessage)
  state.chatting = true
  resetStreamingState()
  streamingState.activeMessage = assistantMessage
  await scrollToBottom(true)
  let streamError = null
  let completeData = null
  chatAbortController = new AbortController()

  try {
    await streamQuestion({
      question,
      sessionId: state.currentSessionId,
      signal: chatAbortController.signal,
      onEvent: async ({ event, data }) => {
        if (event === 'metadata') {
          state.currentSessionId = data?.sessionId || state.currentSessionId
          void loadSessions()
          return
        }
        if (event === 'chunk') {
          enqueueStreamingText(data?.delta || '', assistantMessage)
          return
        }
        if (event === 'complete') {
          completeData = data
          return
        }
        if (event === 'error') {
          streamError = data?.message || '生成失败，请稍后再试。'
        }
      }
    })

    if (streamError) {
      throw new Error(streamError)
    }

    await waitForStreamingDrain()

    if (completeData) {
      if (completeData?.answer) {
        assistantMessage.content = completeData.answer
      }
      finalizeMessageRender(assistantMessage)
      assistantMessage.messageId = completeData?.assistantMessageId || assistantMessage.messageId
      assistantMessage.sources = completeData?.sources || []
      assistantMessage.toolUsed = completeData?.toolUsed || null
      assistantMessage.createdAt = completeData?.createdAt || new Date().toISOString()
      state.currentSessionId = completeData?.sessionId || state.currentSessionId
      syncCurrentSessionTitle()
      await loadSessions()
      await scrollToBottom()
    }

    if (!assistantMessage.content && !completeData?.answer) {
      assistantMessage.content = '本次回复为空，请重试。'
      finalizeMessageRender(assistantMessage)
    }
  } catch (error) {
    await flushStreamingQueue()
    if (error.name === 'AbortError') {
      assistantMessage.content = assistantMessage.content || '已手动停止生成。'
    } else {
      assistantMessage.content = assistantMessage.content || error.message || '提问失败，请检查后端日志。'
    }
    finalizeMessageRender(assistantMessage)
  } finally {
    state.chatting = false
    resetStreamingState()
    chatAbortController = null
  }
}

function stopStreaming() {
  if (chatAbortController) {
    chatAbortController.abort()
  }
}

function enqueueStreamingText(text, message) {
  if (!text) {
    return
  }
  streamingState.activeMessage = message
  streamingState.queue.push(...Array.from(text))
  if (!streamingState.timer) {
    startStreamingTicker()
  }
}

function startStreamingTicker() {
  streamingState.timer = window.setInterval(() => {
    if (!streamingState.activeMessage) {
      resetStreamingState()
      return
    }
    const nextChar = streamingState.queue.shift()
    if (!nextChar) {
      clearInterval(streamingState.timer)
      streamingState.timer = null
      return
    }
    streamingState.activeMessage.content += nextChar
    scheduleScrollToBottom()
  }, 20)
}

async function flushStreamingQueue() {
  if (!streamingState.activeMessage) {
    return
  }
  if (streamingState.queue.length > 0) {
    streamingState.activeMessage.content += streamingState.queue.join('')
  }
  resetStreamingState()
  await scrollToBottom(true)
}

function resetStreamingState() {
  if (streamingState.timer) {
    clearInterval(streamingState.timer)
  }
  streamingState.queue = []
  streamingState.timer = null
  streamingState.activeMessage = null
}

function waitForStreamingDrain() {
  return new Promise((resolve) => {
    if (!streamingState.timer && streamingState.queue.length === 0) {
      resolve()
      return
    }

    const watcher = window.setInterval(() => {
      if (!streamingState.timer && streamingState.queue.length === 0) {
        clearInterval(watcher)
        resolve()
      }
    }, 16)
  })
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
    messages.value.push(buildMessage({
      messageId: `upload-${Date.now()}`,
      role: 'ASSISTANT',
      content: `文档已加入异步处理队列：${data.fileName}，当前状态 ${statusText(data.status)}。`,
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
    }))
  } catch (error) {
    messages.value.push(buildMessage({
      messageId: `upload-error-${Date.now()}`,
      role: 'ASSISTANT',
      content: error.response?.data?.message || '文件上传失败。',
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
    }))
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
    messages.value.push(buildMessage({
      messageId: `retry-${Date.now()}`,
      role: 'ASSISTANT',
      content: `已重新加入处理队列：${doc.fileName}。`,
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
    }))
  } catch (error) {
    messages.value.push(buildMessage({
      messageId: `retry-error-${Date.now()}`,
      role: 'ASSISTANT',
      content: error.response?.data?.message || '重试失败，请稍后再试。',
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
    }))
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
    messages.value.push(buildMessage({
      messageId: `delete-${Date.now()}`,
      role: 'ASSISTANT',
      content: `文档已删除：${doc.fileName}。`,
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
    }))
  } catch (error) {
    messages.value.push(buildMessage({
      messageId: `delete-error-${Date.now()}`,
      role: 'ASSISTANT',
      content: error.response?.data?.message || '删除失败，请稍后再试。',
      sources: [],
      toolUsed: null,
      createdAt: new Date().toISOString()
    }))
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

function buildMessage(message) {
  return {
    ...message,
    streaming: Boolean(message.streaming),
    renderedContent: renderMessageHtml(message.content || '')
  }
}

function finalizeMessageRender(message) {
  message.streaming = false
  message.renderedContent = renderMessageHtml(message.content || '')
}

function renderMessageContent(message) {
  return message.renderedContent || ''
}

function renderMessageHtml(content) {
  const text = content || ''
  try {
    const html = renderMarkdown(text)
    if (html || !text.trim()) {
      return html
    }
  } catch (error) {
    // Fall back to plain text rendering below.
  }
  return renderPlainText(text)
}

function renderPlainText(content) {
  const escaped = (content || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
  return escaped.replaceAll('\n', '<br>')
}

async function scrollToBottom(force = false) {
  await nextTick()
  const container = messagesContainer.value
  if (!container) {
    return
  }
  const distanceToBottom = container.scrollHeight - container.scrollTop - container.clientHeight
  if (force || distanceToBottom < 160) {
    container.scrollTop = container.scrollHeight
  }
}

function scheduleScrollToBottom() {
  if (scrollFramePending) {
    return
  }
  scrollFramePending = true
  requestAnimationFrame(() => {
    scrollFramePending = false
    scrollToBottom()
  })
}

function logout() {
  localStorage.removeItem('token')
  if (documentStream) {
    documentStream.close()
    documentStream = null
  }
  if (chatAbortController) {
    chatAbortController.abort()
    chatAbortController = null
  }
  resetStreamingState()
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

function syncCurrentSessionTitle() {
  if (!state.currentSessionId) {
    state.currentSessionTitle = '新会话'
    return
  }
  const currentSession = sessions.value.find((session) => session.sessionId === state.currentSessionId)
  if (currentSession) {
    state.currentSessionTitle = currentSession.title
    return
  }
  state.currentSessionTitle = `会话 #${state.currentSessionId}`
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
  if (chatAbortController) {
    chatAbortController.abort()
    chatAbortController = null
  }
  resetStreamingState()
})
</script>

<template>
  <div class="chat-page">
    <aside class="sidebar">
      <div>
        <p class="mini">AI Knowledge Base</p>
        <h1>企业知识助手</h1>
      </div>

      <div class="user-card">
        <div class="user-card-top">
          <div>
            <p class="label">当前用户</p>
            <p class="user-name">{{ state.user?.username || '加载中...' }}</p>
          </div>
          <div class="user-badge">{{ state.user?.role || '-' }}</div>
        </div>
        <div class="user-meta-grid">
          <div class="user-meta-item">
            <span class="user-meta-label">userId</span>
            <strong>{{ state.user?.userId || '-' }}</strong>
          </div>
          <div class="user-meta-item">
            <span class="user-meta-label">状态</span>
            <strong>在线</strong>
          </div>
        </div>
      </div>

      <div class="panel session-history-panel">
        <div class="panel-sticky">
          <div class="section-header">
            <p class="label">会话历史</p>
            <button class="tiny-btn" @click="startNewSession">新建</button>
          </div>
          <div class="toolbar">
            <input v-model.trim="state.sessionKeyword" class="search-input" placeholder="搜索会话标题" @keyup.enter="applySessionSearch" />
            <button class="tiny-btn" @click="applySessionSearch">搜索</button>
          </div>
        </div>
        <div class="session-list">
          <div
            v-for="session in sessions"
            :key="session.sessionId"
            class="session-item"
            :class="{ active: isActiveSession(session.sessionId) }"
            @click="openSession(session.sessionId)"
          >
            <div class="session-item-main">
              <strong>{{ session.title }}</strong>
              <span>{{ formatTime(session.updatedAt) }}</span>
            </div>
            <div class="session-actions">
              <button
                class="session-action-btn"
                :disabled="isSessionActioning(session.sessionId)"
                @click.stop="handleRenameSession(session)"
              >
                重命名
              </button>
              <button
                class="session-action-btn danger"
                :disabled="isSessionActioning(session.sessionId)"
                @click.stop="handleDeleteSession(session)"
              >
                删除
              </button>
            </div>
          </div>
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
        <div class="panel-sticky">
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
          <h2>{{ state.currentSessionTitle }}</h2>
        </div>
        <div class="muted">{{ state.sessionLoading ? '会话加载中...' : '会话内容自动保存' }}</div>
      </div>

      <div ref="messagesContainer" class="messages">
        <div
          v-for="message in messages"
          :key="message.messageId"
          class="message"
          :class="message.role === 'USER' ? 'user' : 'assistant'"
        >
          <div class="bubble">
            <div class="role">{{ message.role === 'USER' ? '你' : 'AI助手' }}</div>
            <div
              v-if="message.streaming"
              class="content streaming-content"
            >{{ message.content }}</div>
            <div
              v-else
              class="content message-html"
              v-html="renderMessageContent(message)"
            />
            <div v-if="message.streaming" class="stream-state">
              <span class="typing-cursor" />
              <span>生成中...</span>
            </div>
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
        <div class="composer-actions">
          <button v-if="state.chatting" class="stop-btn" @click="stopStreaming">停止生成</button>
          <button :disabled="state.chatting" @click="handleAsk">
            {{ state.chatting ? '处理中...' : '发送' }}
          </button>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.chat-page {
  height: 100vh;
  display: grid;
  grid-template-columns: 360px 1fr;
  overflow: hidden;
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
  margin: 8px 0 18px;
  font-size: 32px;
  line-height: 1.06;
  letter-spacing: -0.02em;
}

.panel {
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.74);
  border: 1px solid var(--line);
  margin-bottom: 16px;
}

.user-card {
  padding: 14px 16px;
  border-radius: 18px;
  margin-bottom: 14px;
  background: rgba(255, 255, 255, 0.42);
  border: 1px solid rgba(148, 163, 184, 0.16);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

.user-card-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.user-name {
  margin: 4px 0 0;
  font-size: 16px;
  line-height: 1.2;
  color: rgba(15, 23, 42, 0.9);
}

.user-badge {
  flex-shrink: 0;
  padding: 5px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: rgba(15, 23, 42, 0.72);
  background: rgba(15, 23, 42, 0.06);
}

.user-meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.user-meta-item {
  display: grid;
  gap: 4px;
  padding: 8px 10px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.5);
}

.user-meta-item strong {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.8);
}

.user-meta-label {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: rgba(100, 116, 139, 0.9);
}

.session-history-panel,
.docs-panel {
  display: flex;
  flex-direction: column;
}

.panel-sticky {
  position: sticky;
  top: -18px;
  z-index: 3;
  margin: -18px -18px 10px;
  padding: 16px 18px 12px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(255, 255, 255, 0.78));
  backdrop-filter: blur(16px);
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.05);
}

.panel-sticky .label {
  font-size: 12px;
  letter-spacing: 0.04em;
}

.panel-sticky .toolbar {
  margin-top: 8px;
  gap: 8px;
}

.panel-sticky .section-header {
  gap: 12px;
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

.label,
.muted,
.doc-owner,
.time {
  color: var(--muted);
}

.label {
  font-size: 11px;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.search-input,
textarea {
  width: 100%;
  border-radius: 14px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.9);
  padding: 12px 14px;
}

.panel-sticky .search-input {
  padding: 9px 12px;
  font-size: 12px;
  border-radius: 12px;
  border-color: rgba(148, 163, 184, 0.22);
  background: rgba(255, 255, 255, 0.78);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.32);
}

.panel-sticky .tiny-btn {
  padding: 7px 10px;
  font-size: 11px;
  border-radius: 12px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.88), rgba(241, 245, 249, 0.82));
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.04);
  color: rgba(15, 23, 42, 0.74);
}

.panel-sticky .tiny-btn:hover:not(:disabled) {
  border-color: rgba(29, 78, 216, 0.22);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(219, 234, 254, 0.78));
}

.tiny-btn,
.doc-action,
.upload,
.logout,
.composer button,
.session-action-btn {
  border: none;
  border-radius: 14px;
  cursor: pointer;
}

.tiny-btn {
  padding: 8px 12px;
  background: rgba(15, 23, 42, 0.07);
  font-size: 12px;
}

.session-list {
  display: grid;
  gap: 10px;
  max-height: 32vh;
  overflow: auto;
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: rgba(29, 78, 216, 0.32) rgba(148, 163, 184, 0.08);
}

.session-item {
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid transparent;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
}

.session-item.active {
  border-color: rgba(29, 78, 216, 0.28);
  background: rgba(29, 78, 216, 0.08);
}

.session-item-main {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.session-item-main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 1.35;
}

.session-item span {
  font-size: 11px;
  color: var(--muted);
}

.session-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.session-action-btn {
  padding: 5px 9px;
  background: rgba(15, 23, 42, 0.06);
  font-size: 11px;
}

.session-action-btn:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.session-action-btn.danger {
  color: #b91c1c;
  background: rgba(185, 28, 28, 0.08);
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
  padding-right: 14px;
  scrollbar-width: thin;
  scrollbar-color: rgba(15, 118, 110, 0.3) rgba(148, 163, 184, 0.08);
}

.doc-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}

.doc-item:last-child {
  border-bottom: none;
}

.doc-top strong {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 1.35;
}

.doc-owner,
.doc-meta,
.time {
  font-size: 11px;
}

.status {
  font-size: 11px;
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
  padding: 6px 10px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.9);
  font-size: 11px;
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
  font-size: 11px;
}

.doc-error {
  margin-top: 6px;
  color: var(--danger);
  font-size: 11px;
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
  min-height: 0;
  overflow: hidden;
}

.chat-header h2 {
  margin: 6px 0 0;
}

.messages {
  min-height: 0;
  overflow: auto;
  padding-right: 4px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.34);
  border: 1px solid rgba(148, 163, 184, 0.18);
  padding: 18px 14px 8px;
  scrollbar-width: thin;
  scrollbar-color: rgba(29, 78, 216, 0.24) rgba(148, 163, 184, 0.08);
}

.session-list::-webkit-scrollbar,
.docs-panel::-webkit-scrollbar,
.messages::-webkit-scrollbar {
  width: 8px;
}

.session-list::-webkit-scrollbar-track,
.docs-panel::-webkit-scrollbar-track,
.messages::-webkit-scrollbar-track {
  background: rgba(148, 163, 184, 0.08);
  border-radius: 999px;
}

.session-list::-webkit-scrollbar-thumb,
.docs-panel::-webkit-scrollbar-thumb,
.messages::-webkit-scrollbar-thumb {
  border-radius: 999px;
  border: 2px solid transparent;
  background-clip: padding-box;
}

.session-list::-webkit-scrollbar-thumb {
  background-color: rgba(29, 78, 216, 0.32);
}

.docs-panel::-webkit-scrollbar-thumb {
  background-color: rgba(15, 118, 110, 0.3);
}

.messages::-webkit-scrollbar-thumb {
  background-color: rgba(29, 78, 216, 0.24);
}

.session-list::-webkit-scrollbar-thumb:hover {
  background-color: rgba(29, 78, 216, 0.46);
}

.docs-panel::-webkit-scrollbar-thumb:hover {
  background-color: rgba(15, 118, 110, 0.42);
}

.messages::-webkit-scrollbar-thumb:hover {
  background-color: rgba(29, 78, 216, 0.36);
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
  line-height: 1.7;
}

.streaming-content {
  white-space: pre-wrap;
}

.stream-state {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  color: var(--muted);
  font-size: 13px;
}

.typing-cursor {
  width: 8px;
  height: 18px;
  border-radius: 999px;
  background: currentColor;
  animation: blink 1s steps(1) infinite;
}

.message-html :deep(h1),
.message-html :deep(h2),
.message-html :deep(h3),
.message-html :deep(h4) {
  margin: 1.1em 0 0.55em;
  line-height: 1.3;
}

.message-html :deep(p),
.message-html :deep(ul),
.message-html :deep(ol),
.message-html :deep(blockquote),
.message-html :deep(pre),
.message-html :deep(table) {
  margin: 0.8em 0;
}

.message-html :deep(ul),
.message-html :deep(ol) {
  padding-left: 1.4em;
}

.message-html :deep(code) {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 0.92em;
  background: rgba(15, 23, 42, 0.08);
  border-radius: 6px;
  padding: 0.16em 0.38em;
}

.message-html :deep(pre) {
  overflow: auto;
  border-radius: 16px;
  padding: 14px 16px;
  background: #0f172a;
  color: #e2e8f0;
}

.message-html :deep(pre code) {
  display: block;
  background: transparent;
  padding: 0;
  color: inherit;
}

.message-html :deep(blockquote) {
  border-left: 4px solid rgba(29, 78, 216, 0.28);
  padding-left: 14px;
  color: var(--muted);
}

.message-html :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.message-html :deep(th),
.message-html :deep(td) {
  border: 1px solid rgba(148, 163, 184, 0.25);
  padding: 8px 10px;
  text-align: left;
}

.message-html :deep(a) {
  color: inherit;
  text-decoration: underline;
}

.message.user .message-html :deep(code) {
  background: rgba(255, 255, 255, 0.16);
}

.message.user .message-html :deep(blockquote) {
  border-left-color: rgba(255, 255, 255, 0.4);
  color: rgba(255, 255, 255, 0.82);
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

.composer-actions {
  display: grid;
  gap: 10px;
}

textarea {
  min-height: 110px;
  resize: vertical;
}

.composer button {
  background: linear-gradient(90deg, var(--accent), var(--accent-2));
  color: white;
}

.stop-btn {
  background: rgba(220, 38, 38, 0.12);
  color: #b91c1c;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
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
