import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const service = (port: number) => ({
  target: `http://localhost:${port}`,
  rewrite: (path: string) => path.replace(/^\/svc\/\w+/, ''),
})

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Mỗi service 1 cổng; proxy để trình duyệt chỉ nói chuyện với 1 origin (không cần CORS).
    proxy: {
      '/svc/order': service(8091),
      '/svc/inventory': service(8092),
      '/svc/payment': service(8093),
    },
  },
})
