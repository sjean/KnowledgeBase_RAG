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

export function renameChatSession(sessionId, title) {
  return http.patch(`/chat/sessions/${sessionId}`, { title })
}

export function deleteChatSession(sessionId) {
  return http.delete(`/chat/sessions/${sessionId}`)
}

export async function streamQuestion({ question, sessionId = null, signal, onEvent }) {
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
  const token = localStorage.getItem('token')
  const response = await fetch(`${baseUrl}/chat/ask/stream`, {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify({ question, sessionId }),
    signal
  })

  if (!response.ok) {
    const text = await response.text()
    let message = text || '流式请求失败'
    try {
      message = JSON.parse(text).message || message
    } catch (error) {
      // Ignore JSON parse failure and keep raw text.
    }
    throw new Error(message)
  }

  if (!response.body) {
    throw new Error('浏览器不支持流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split('\n\n')
    buffer = frames.pop() || ''
    for (const frame of frames) {
      const parsed = parseSseFrame(frame)
      if (parsed) {
        await onEvent(parsed)
      }
    }
  }

  const tail = decoder.decode()
  if (tail) {
    buffer += tail
  }
  if (buffer.trim()) {
    const parsed = parseSseFrame(buffer)
    if (parsed) {
      await onEvent(parsed)
    }
  }
}

function parseSseFrame(frame) {
  const lines = frame
    .split('\n')
    .map((line) => line.trimEnd())
    .filter(Boolean)

  if (lines.length === 0) {
    return null
  }

  let event = 'message'
  const dataLines = []

  lines.forEach((line) => {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      return
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim())
    }
  })

  const raw = dataLines.join('\n')
  if (!raw) {
    return { event, data: null }
  }

  try {
    return { event, data: JSON.parse(raw) }
  } catch (error) {
    return { event, data: raw }
  }
}
