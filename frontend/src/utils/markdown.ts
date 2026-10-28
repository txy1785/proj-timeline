import MarkdownIt from 'markdown-it'

/**
 * 进展记录正文的 Markdown 口径。
 * <p>
 * 粘贴的附件先上传为任务附件，再在正文里以 `attachment:{附件 id}` 占位：
 * 图片渲染成内联预览，其他文件渲染成可点击的超链接，文件本体始终由附件接口按权限读取。
 * 渲染使用 `html:false`，用户输入的 HTML 一律转义，不引入 DOMPurify；
 * 外链统一新标签页打开，附件链接由 `MarkdownView` 的事件委托接管，不会跳出应用。
 */
const ATTACHMENT_PREFIX = 'attachment:'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

/** 从 `attachment:{id}` 取出附件 id；不是附件占位时返回 null。 */
export function attachmentIdOf(url: string): number | null {
  if (!url.startsWith(ATTACHMENT_PREFIX)) return null
  const id = Number(url.slice(ATTACHMENT_PREFIX.length))
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

const escape = (value: string) => md.utils.escapeHtml(value)

// 图片：附件占位先输出容器，由 MarkdownView 取回 Blob 后填充，避免 <img> 无法携带 Authorization
md.renderer.rules.image = (tokens, idx) => {
  const token = tokens[idx]
  const src = String(token.attrGet('src') || '')
  const alt = token.content || ''
  const attachmentId = attachmentIdOf(src)
  if (attachmentId) {
    return `<span class="md-image" data-attachment-id="${attachmentId}" data-name="${escape(alt)}">`
      + '<span class="md-image-state">图片加载中…</span></span>'
  }
  const title = token.attrGet('title')
  // 外链图片不携带 Referer，减少向第三方泄露来源页面地址
  return `<img src="${escape(src)}" alt="${escape(alt)}"${title ? ` title="${escape(String(title))}"` : ''} loading="lazy" referrerpolicy="no-referrer">`
}

const defaultLinkOpen = md.renderer.rules.link_open
  ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

// 链接：附件占位改写成可点击的文件占位；普通外链加 target/rel
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const attachmentId = attachmentIdOf(String(token.attrGet('href') || ''))
  if (attachmentId) {
    token.attrSet('href', '#')
    token.attrSet('class', 'md-file')
    token.attrSet('data-attachment-id', String(attachmentId))
    return self.renderToken(tokens, idx, options)
  }
  token.attrSet('target', '_blank')
  token.attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

/** Markdown → 安全 HTML；内容来自 `v-html`，样式使用全局 `.markdown-body`。 */
export function renderMarkdown(source: string): string {
  return md.render(source || '')
}

/** 在正文里插入附件占位：图片用 ![](...)，其他文件用 [] (...)，文件名按 Markdown 规则转义。 */
export function attachmentMarkdown(fileName: string, id: number, image: boolean): string {
  const label = fileName.replace(/([\\[\]])/g, '\\$1')
  return image ? `![${label}](${ATTACHMENT_PREFIX}${id})` : `[${label}](${ATTACHMENT_PREFIX}${id})`
}

/** 记录名统一按 `.md` 文件名保存，缺后缀时补上。 */
export function markdownFileName(title: string): string {
  const name = title.trim()
  if (!name) return '进展记录.md'
  return name.toLowerCase().endsWith('.md') ? name : `${name}.md`
}

/** 下载记录为 .md 文件：内容来自已保存的记录，不走附件接口。 */
export function downloadMarkdown(title: string, content: string): void {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = markdownFileName(title)
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
