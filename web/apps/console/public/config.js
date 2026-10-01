// Runtime configuration of the console. In the container image this file is replaced at start-up from the environment
// (NL_AUTH_ORIGIN, see web/docker/studio.conf.template, which the console image shares). Empty here: the build-time
// VITE_NL_AUTH_ORIGIN or the local default applies (src/lib/auth-server.ts).
window.__NL_CONFIG__ = window.__NL_CONFIG__ || {};
