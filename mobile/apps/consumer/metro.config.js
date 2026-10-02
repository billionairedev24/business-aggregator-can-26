// Expo's defaults handle the pnpm workspace (watchFolders and nodeModulesPaths from mobile/). Two additions:
//  - @northline/tokens is linked from web/packages/tokens (outside the workspace): Metro must watch it;
//  - React, React Native and react-query are always resolved from this app, so a shared package's devDependency
//    copies never put a second React in the bundle.
const path = require('path');
const { getDefaultConfig } = require('expo/metro-config');

const projectRoot = __dirname;
const config = getDefaultConfig(projectRoot);
const tokens = path.resolve(projectRoot, '../../../web/packages/tokens');
config.watchFolders = [...(config.watchFolders ?? []), tokens];

const SINGLETONS = ['react', 'react-dom', 'react-native', 'react-native-web', '@tanstack/react-query'];
const appOrigin = path.join(projectRoot, 'package.json');
const upstream = config.resolver.resolveRequest;
config.resolver.resolveRequest = (context, moduleName, platform) => {
  const resolve = upstream ?? context.resolveRequest;
  if (SINGLETONS.some((s) => moduleName === s || moduleName.startsWith(s + '/'))) {
    return resolve({ ...context, originModulePath: appOrigin }, moduleName, platform);
  }
  return resolve(context, moduleName, platform);
};

module.exports = config;
