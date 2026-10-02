const path = require('path');

const fromApp = (m) => path.dirname(require.resolve(`${m}/package.json`, { paths: [__dirname] }));

/** @type {import('jest').Config} */
module.exports = {
  preset: 'jest-expo',
  setupFiles: ['<rootDir>/jest.setup.ts'],
  testMatch: ['**/__tests__/**/*.test.[jt]s?(x)'],
  testPathIgnorePatterns: ['/node_modules/', '/e2e/', '/dist-'],
  transformIgnorePatterns: [
    'node_modules/(?!(\\.pnpm|(jest-)?react-native|@react-native(-community)?|@react-native-async-storage|expo(nent)?|@expo(nent)?/.*|@expo-google-fonts/.*|expo-.*|react-navigation|@react-navigation/.*|react-native-svg|standard-navigation|@noble/.*|@tanstack/.*))',
  ],
  // One React and one react-query for the app and the shared kit (same as metro.config.js).
  moduleNameMapper: {
    '^react$': fromApp('react'),
    '^react/(.*)$': fromApp('react') + '/$1',
    '^@tanstack/react-query$': fromApp('@tanstack/react-query'),
  },
};
