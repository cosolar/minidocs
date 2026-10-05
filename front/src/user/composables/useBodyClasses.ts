import { onBeforeUnmount, watch, type Ref } from 'vue'

/**
 * 按视图声明式地给 {@code <body>} 挂/摘状态类。
 *
 * <p>样式表里 `md-page--read`、`md-page--error` 这类类名挂在 body 上（原本由 Thymeleaf 模板静态写死），
 * SPA 只有一个 html 入口，只能由当前视图在运行时切换。这里记录已挂载的类，卸载时精确回收，
 * 避免视图切换后残留导致下一页布局错位。</p>
 */
export function useBodyClasses(classes: Ref<string[]>) {
  const applied = new Set<string>()

  const sync = () => {
    const next = new Set(classes.value.filter(Boolean))
    applied.forEach((name) => {
      if (!next.has(name)) {
        document.body.classList.remove(name)
        applied.delete(name)
      }
    })
    next.forEach((name) => {
      if (!applied.has(name)) {
        document.body.classList.add(name)
        applied.add(name)
      }
    })
  }

  watch(classes, sync, { immediate: true })

  onBeforeUnmount(() => {
    applied.forEach((name) => document.body.classList.remove(name))
    applied.clear()
  })
}
