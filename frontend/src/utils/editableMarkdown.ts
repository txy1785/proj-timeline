import TurndownService from 'turndown'
import { gfm } from 'turndown-plugin-gfm'
import { attachmentMarkdown } from './markdown'

/**
 * 富文本编辑区 ↔ Markdown 的转换口径。
 * <p>
 * 编辑区显示的是 Markdown 渲染后的内容（图片、标题、列表等直接可见），输入时用 turndown
 * 把 DOM 序列化回 Markdown 保存，保证记录仍以 .md 文本存储。
 * `md-image` / `md-file` 是本应用的附件占位，必须优先于普通图片/链接规则，写回 `attachment:{id}`。
 */
export function createMarkdownTurndown(): TurndownService {
  const service = new TurndownService({
    headingStyle: 'atx',
    hr: '---',
    bulletListMarker: '-',
    codeBlockStyle: 'fenced',
    fence: '```',
    emDelimiter: '*',
    strongDelimiter: '**',
    linkStyle: 'inlined'
  })
  service.use(gfm)

  service.addRule('attachmentImage', {
    filter: (node) => {
      if (node.nodeName === 'SPAN') {
        const element = node as HTMLElement
        return element.classList.contains('md-image') && !!element.dataset.attachmentId
      }
      return node.nodeName === 'IMG' && !!(node as HTMLElement).dataset.attachmentId
    },
    replacement: (_content, node) => {
      const element = node as HTMLElement
      const name = element.dataset.name || element.getAttribute('alt') || '附件图片'
      return attachmentMarkdown(name, Number(element.dataset.attachmentId), true)
    }
  })

  service.addRule('attachmentLink', {
    filter: (node) => node.nodeName === 'A' && !!(node as HTMLElement).dataset.attachmentId,
    replacement: (content, node) => {
      const element = node as HTMLElement
      const name = (content || element.textContent || '').trim() || '附件'
      return attachmentMarkdown(name, Number(element.dataset.attachmentId), false)
    }
  })

  service.addRule('strikethrough', {
    filter: ['del', 's'],
    replacement: (content) => `~~${content}~~`
  })

  service.addRule('fencedCode', {
    filter: (node) => node.nodeName === 'PRE' && node.firstChild?.nodeName === 'CODE',
    replacement: (_content, node) => {
      const code = node.firstChild as HTMLElement
      const language = (code.className.match(/language-([\w-]+)/) || [])[1] || ''
      return `\n\`\`\`${language}\n${(code.textContent || '').replace(/\n$/, '')}\n\`\`\`\n`
    }
  })

  // 渲染器开启 breaks，单个换行就是 <br>，序列化回单个换行而不是 Markdown 硬换行
  service.addRule('lineBreak', {
    filter: 'br',
    replacement: () => '\n'
  })

  return service
}

/** 从 Markdown 正文中移除某个附件的全部占位（图片/链接），用于源码模式或移除上传。 */
export function removeAttachmentPlaceholders(source: string, attachmentId: number): string {
  const pattern = new RegExp(`!?\\[(?:[^\\]\\\\]|\\\\.)*\\]\\(attachment:${attachmentId}\\)\\n?`, 'g')
  return source.replace(pattern, '')
}
