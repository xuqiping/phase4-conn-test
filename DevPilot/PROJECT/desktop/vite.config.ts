import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Tauri 约定：固定 1420 端口，frontendDist 指向 ../dist
export default defineConfig({
  root: "src-ui",
  plugins: [react(), tailwindcss()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
  },
  build: {
    outDir: "../dist",
    emptyOutDir: true,
    target: "es2021",
  },
  test: {
    include: ["**/*.test.{ts,tsx}"],
    environment: "jsdom",
  },
} as never);
