/*
  The LP page's three.js turntable (준: "디테일한 three js로 해줘", "플스4느낌"), loaded as a module after vinyl.js, with three.js
  from the app ("three" → /__gyeot/lib/three.module.js, the import map before it). Not drawn yet: until it is, the piece's own
  CSS deck shows, as it does whenever WebGL is not there.

  What it builds on (vinyl.js, window.__gy): onFrame(fn(dt)) runs in the piece's own frame (one loop, stopped while hidden);
  state() gives spin (the record's and platter's turn, degrees), armAngle (degrees about the pivot at 850,150; 90 is the rest)
  and armLift (0 down … 1 up), motorOn, omega, albumIdx; onTrack(fn) says when the record, the track or 내 음악's list changed;
  label() gives the label's SVG group and a key that changes when it looks different; grooves() the tracks' radii (deck
  units, 300 = the record's edge); report(stats) sends the frame times once to the app's diagnostic report. The slot's
  fly-out and fly-in when the record changes are Web Animations on #slot (read its computed transform and opacity).
*/
export {};
