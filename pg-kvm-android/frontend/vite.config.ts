import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 开发时将 WebSocket 代理到信令服务器
      "/ws": {
        target: "ws://localhost:3000",
        ws: true,
      },
    },
  },
});