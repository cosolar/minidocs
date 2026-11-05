import { onBeforeUnmount, ref } from 'vue'

/**
 * 「分享这一页」的动作：优先系统分享面板，退回复制链接。
 *
 * <p>桌面顶栏与手机底栏各有一颗分享按钮，两处都得完整走过同一条降级链，所以抽在这里：
 * 复制一份到第二个组件里，迟早一边补了兜底、另一边忘了。</p>
 *
 * <p>{@code navigator.share} 在手机上能直接进微信 / 邮件；用户取消不是错误，
 * 但也**不能**接着弹「已复制」—— 那会让人以为自己刚取消的动作其实已经生效。</p>
 *
 * <p>剪贴板 {@code navigator.clipboard} 带 {@code [SecureContext]} 门：http 非 localhost
 * 部署下它压根不存在，权限被拒时也会抛错。没有 textarea 兜底，这些环境里点分享毫无反应，
 * 读者只会以为按钮坏了。</p>
 */
export function useShareLink(url: () => string, title: () => string) {
  const copied = ref(false)
  let timer: number | undefined

  onBeforeUnmount(() => window.clearTimeout(timer))

  async function copy(text: string): Promise<boolean> {
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text)
        return true
      } catch {
        // 权限被拒，落到下面的兜底
      }
    }
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.top = '-1000px'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    let ok = false
    try {
      ok = document.execCommand('copy')
    } catch {
      ok = false
    }
    area.remove()
    return ok
  }

  async function share() {
    if (typeof navigator.share === 'function') {
      try {
        await navigator.share({ title: title(), url: url() })
        return
      } catch {
        // 取消分享不是错误，也不该接着弹「已复制」
        return
      }
    }
    if (await copy(url())) {
      copied.value = true
      window.clearTimeout(timer)
      timer = window.setTimeout(() => (copied.value = false), 1600)
    }
  }

  return { copied, share }
}
