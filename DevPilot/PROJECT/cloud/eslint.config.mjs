// 云端最小 ESLint（flat config，CI 硬拦截 --max-warnings 0）
import eslint from "@eslint/js";
import tseslint from "@typescript-eslint/eslint-plugin";
import tsparser from "@typescript-eslint/parser";
import globals from "globals";

export default [
  { ignores: ["dist/", "node_modules/"] },
  {
    files: ["src/**/*.ts"],
    languageOptions: { parser: tsparser, globals: globals.node },
    plugins: { "@typescript-eslint": tseslint },
    rules: {
      ...eslint.configs.recommended.rules,
      ...tseslint.configs.recommended.rules,
      "@typescript-eslint/no-explicit-any": "off",
    },
  },
  {
    files: ["src/**/*.spec.ts", "test/**/*.ts", "scripts/**/*.ts"],
    languageOptions: {
      parser: tsparser,
      globals: { ...globals.node, ...globals.jest },
    },
    plugins: { "@typescript-eslint": tseslint },
    rules: {
      ...eslint.configs.recommended.rules,
      ...tseslint.configs.recommended.rules,
      "@typescript-eslint/no-explicit-any": "off",
      "@typescript-eslint/no-unused-vars": "off",
    },
  },
];
