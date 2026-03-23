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

export function retryDocument(documentId) {
  return http.post(`/file/documents/${documentId}/retry`)
}

export function deleteDocument(documentId) {
  return http.delete(`/file/documents/${documentId}`)
}
