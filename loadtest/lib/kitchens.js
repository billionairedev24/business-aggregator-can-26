// S-119: how many kitchens have screens open in this run (main.js sets it from the profile in every VU's init); the
// checkout_food scenario sends its orders to those kitchens, the kds scenario opens their screens.
let withScreens = 0;

export function setKitchensWithScreens(count) {
  withScreens = count;
}

export function kitchensWithScreens(total) {
  return Math.max(1, Math.min(total, withScreens || total));
}
