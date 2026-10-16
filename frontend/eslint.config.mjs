import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // The design tools of the site live outside this repository.
    "tools/**",
    // Not part of the Next.js app: the Astor static site (own tests in CI) and the local media.
    "astor-butler/**",
    "public/**",
  ]),
]);

export default eslintConfig;
