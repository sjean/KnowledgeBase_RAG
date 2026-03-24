import DOMPurify from 'dompurify'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import { marked } from 'marked'

marked.setOptions({
  breaks: true,
  gfm: true,
  async: false
})

const renderer = new marked.Renderer()

renderer.code = ({ text, lang }) => {
  const language = lang && hljs.getLanguage(lang) ? lang : 'plaintext'
  const highlighted = hljs.highlight(text || '', { language }).value
  return [
    '<pre><code class="hljs language-' + language + '">',
    highlighted,
    '</code></pre>'
  ].join('')
}

marked.use({ renderer })

function sanitizeHref(href) {
  if (typeof href !== 'string' || !href.trim()) {
    return '#'
  }
  const value = href.trim()
  if (/^(https?:|mailto:|tel:)/i.test(value)) {
    return value
  }
  if (value.startsWith('/') || value.startsWith('#')) {
    return value
  }
  return '#'
}

export function renderMarkdown(content) {
  const rawHtml = marked.parse((content || '').replaceAll('\r\n', '\n'))
  const sanitizedHtml = DOMPurify.sanitize(rawHtml, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['target', 'rel', 'class']
  })
  if (typeof document === 'undefined') {
    return sanitizedHtml
  }

  const container = document.createElement('div')
  container.innerHTML = sanitizedHtml
  container.querySelectorAll('a').forEach((anchor) => {
    anchor.setAttribute('href', sanitizeHref(anchor.getAttribute('href')))
    anchor.setAttribute('target', '_blank')
    anchor.setAttribute('rel', 'noopener noreferrer')
  })
  return container.innerHTML
}
