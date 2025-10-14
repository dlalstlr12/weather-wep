import React from 'react'

// Very lightweight markdown renderer without extra deps.
// Supports: headings(#), bold(**), italics(*), inline code(`), code blocks(```),
// links [text](url), lists, line breaks.
// NOTE: This is intentionally simple and not fully spec-compliant.

function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function renderInline(md: string) {
  // code
  md = md.replace(/`([^`]+)`/g, (_m, p1) => `<code>${escapeHtml(p1)}</code>`)
  // bold
  md = md.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  // italics
  md = md.replace(/\*([^*]+)\*/g, '<em>$1</em>')
  // links
  md = md.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer noopener">$1</a>')
  return md
}

function renderBlocks(md: string) {
  const lines = md.split(/\r?\n/)
  const out: string[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    // fenced code block
    if (/^```/.test(line)) {
      const buf: string[] = []
      i++
      while (i < lines.length && !/^```/.test(lines[i])) {
        buf.push(lines[i])
        i++
      }
      // consume closing fence
      if (i < lines.length) i++
      out.push(`<pre><code>${escapeHtml(buf.join('\n'))}</code></pre>`) 
      continue
    }
    // heading
    const h = line.match(/^(#{1,6})\s+(.*)$/)
    if (h) {
      const level = h[1].length
      out.push(`<h${level}>${renderInline(h[2])}</h${level}>`)
      i++
      continue
    }
    // list
    if (/^\s*[-*+]\s+/.test(line)) {
      const items: string[] = []
      while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
        const itemText = lines[i].replace(/^\s*[-*+]\s+/, '')
        items.push(`<li>${renderInline(itemText)}</li>`)
        i++
      }
      out.push(`<ul>${items.join('')}</ul>`) 
      continue
    }
    // paragraph or empty
    if (line.trim().length === 0) {
      out.push('')
      i++
      continue
    }
    // paragraph merge consecutive non-empty lines
    const para: string[] = [line]
    i++
    while (i < lines.length && lines[i].trim().length > 0 && !/^```/.test(lines[i])) {
      para.push(lines[i])
      i++
    }
    out.push(`<p>${renderInline(para.join(' '))}</p>`) 
  }
  return out.join('\n')
}

export default function Markdown({ children }: { children: string }) {
  const html = React.useMemo(() => renderBlocks(children || ''), [children])
  return <div className="markdown" dangerouslySetInnerHTML={{ __html: html }} />
}
