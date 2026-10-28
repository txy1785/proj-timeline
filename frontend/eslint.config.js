import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'

export default [
  { ignores: ['dist/**', 'node_modules/**', 'src/api/schema.d.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  { files: ['tests/**/*.mjs'], languageOptions: { globals: { ...globals.browser, ...globals.node } } },
  { files: ['**/*.ts'], languageOptions: { parser: tseslint.parser, globals: globals.browser } },
  { files: ['**/*.vue'], languageOptions: { parser: vueParser, parserOptions: { parser: tseslint.parser, extraFileExtensions: ['.vue'], sourceType: 'module' }, globals: globals.browser }, rules: {
    '@typescript-eslint/no-explicit-any': 'off', 'vue/multi-word-component-names': 'off',
    'vue/max-attributes-per-line': 'off', 'vue/singleline-html-element-content-newline': 'off',
    'vue/html-indent': 'off', 'vue/html-closing-bracket-newline': 'off',
    'vue/html-closing-bracket-spacing': 'off', 'vue/mustache-interpolation-spacing': 'off',
    'vue/multiline-html-element-content-newline': 'off', 'vue/html-self-closing': 'off'
  } }
]
