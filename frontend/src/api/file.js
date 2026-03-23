import http from './http'

export function uploadFile(file) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post('/file/upload', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

export function fetchDocuments() {
  return http.get('/file/documents')
}

export function fetchDocumentsPage(params) {
  return http.get('/file/documents', { params })
}

export function retryDocument(documentId) {
  return http.post(`/file/documents/${documentId}/retry`)
}

export function deleteDocument(documentId) {
  return http.delete(`/file/documents/${documentId}`)
}

export function buildDocumentStreamUrl() {
  const token = localStorage.getItem('token')
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
  const url = new URL(`${window.location.origin}${baseUrl}/file/documents/stream`)
  if (token) {
    url.searchParams.set('access_token', token)
  }
  return url.toString()
}
