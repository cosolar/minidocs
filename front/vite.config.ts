import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 单 SPA 构建配置（F11）：一个入口、一次构建。
 *
 * <p><b>页面基址与后端前缀是分开的两件事：</b>产物（{@code base}、vue-router base）默认在站点根
 * {@code /}，由 Nginx 之类的静态服务器伺服；后端仍挂在 {@code server.servlet.context-path}
 * （默认 {@code /minidocs}）之下，接口与它拼出来的地址都带这一段。两侧前缀在
 * {@code src/shared/appBase.ts} 里分别定义（{@code PAGE_BASE} / {@code BACKEND_BASE}）。</p>
 *
 * <p>要改后端前缀：给后端 {@code CONTEXT_PATH}，给前端 {@code VITE_BACKEND_BASE}，只改一边必然 404。
 * 若希望沿用「页面也挂在同一前缀下」的单jar 部署，把 {@code base} 改成 {@code '/minidocs/'} 即可，
 * 那一层由 {@code PAGE_BASE} 自动套上。</p>
 */

/** 默认后端 context-path，与后端 application.yml 的默认值一致。 */
const DEFAULT_BACKEND_BASE = '/minidocs'

/** 产物目录：前端独立部署，构建结果就是一份可交给静态服务器的目录。 */
const OUT_DIR = 'dist'

export default defineConfig(({ mode }) => {
  // VITE_* 才会注入 import.meta.env；开发期这里读的是同一份 .env，避免代理与运行时各说各话。
  // 用配置文件自身的位置当 env 目录，而不是 process.cwd()：tsconfig 的 types 只放了 vite/client，
  // Node 的全局（process）不在其中；而下面 fileURLToPath 已经从 node:url 导入了。
  const env = loadEnv(mode, fileURLToPath(new URL('.', import.meta.url)))
  const backend = (env.VITE_BACKEND_BASE || DEFAULT_BACKEND_BASE).replace(/\/+$/, '')
  const api = `${backend}/api`

  return {
    base: '/',
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      port: 5173,
      proxy: {
        // 后端 context-path 之下的一切原样透传，不做 rewrite：后端自己按 context-path 解析
        [api]: { target: 'http://localhost:9098', changeOrigin: true },
        // 只代理资源代理接口，不代理 /kb/{org}/{slug} 与 /share/{token} 本身，
        // 否则开发期直接刷新这两个页面会被后端接管，SPA 路由失效。
        [`^${backend}/kb/[^/]+/[^/]+/asset/`]: { target: 'http://localhost:9098', changeOrigin: true },
        [`^${backend}/share/[^/]+/asset/`]: { target: 'http://localhost:9098', changeOrigin: true },
        // 后端自带的接口文档（账号设置里的开发入口），开发期一并代理
        [`${backend}/swagger-ui`]: { target: 'http://localhost:9098', changeOrigin: true },
        [`${backend}/v3/api-docs`]: { target: 'http://localhost:9098', changeOrigin: true }
      }
    },
    define: {
      /*
       * 只注入后端前缀这一个键：appBase.ts 读 import.meta.env.VITE_BACKEND_BASE，
       * Vite 自己也会把 .env 里的 VITE_* 注入到 import.meta.env，这里显式再给一份，
       * 保证「dev 代理用的后端前缀」与「运行时拿到的后端前缀」永远同源（都不配时回落 /minidocs）。
       */
      'import.meta.env.VITE_BACKEND_BASE': JSON.stringify(backend)
    },
    build: {
      outDir: OUT_DIR,
      emptyOutDir: true,
      // mermaid + bytemd + element-plus 各自成块，单块超过 500kB 是这些库的正常体积
      chunkSizeWarningLimit: 4600
    }
  }
})
