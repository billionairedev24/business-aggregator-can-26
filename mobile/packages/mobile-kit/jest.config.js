/** @type {import('jest').Config} */
module.exports = {
  preset: 'jest-expo',
  setupFiles: ['<rootDir>/jest.setup.ts'],
  testMatch: ['**/__tests__/**/*.test.[jt]s?(x)'],
  // pnpm keeps packages under node_modules/.pnpm/<id>/node_modules/<name>; ESM packages (Expo, React Native, noble)
  // are transformed wherever they sit.
  transformIgnorePatterns: [
    'node_modules/(?!(\\.pnpm|(jest-)?react-native|@react-native(-community)?|expo(nent)?|@expo(nent)?/.*|expo-.*|@noble/.*))',
  ],
};
