// eslint 9 flat config · computer-use-skill
import tseslint from "typescript-eslint";

export default tseslint.config(
  ...tseslint.configs.recommended,
  {
    rules: {
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
      "@typescript-eslint/consistent-type-imports": "warn"
    }
  },
  { ignores: ["dist/", "node_modules/", "scripts/"] }
);
