import http from './http'

export function askQuestion(question, sessionId = null) {
  return http.post('/chat/ask', { question, sessionId })
}

export function fetchChatSessions(params) {
  return http.get('/chat/sessions', { params })
}

export function fetchChatSessionDetail(sessionId) {
  return http.get(`/chat/sessions/${sessionId}`)
}
