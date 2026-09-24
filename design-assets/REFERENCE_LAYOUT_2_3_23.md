# NING 2.3.23 layout baseline

Reference: `dist/NING-2.3.23-release.apk`, inspected without downgrading the phone.
The approved phone uses 1264 × 2780 pixels at density 3. Layout measurements
from that APK are converted using `screenWidth × oldDp × 3 / 1264`, so a
1080-pixel-wide phone preserves the proportions. Text also respects fontScale.
Canvas decoration still uses its existing 1080-pixel design grid.

Verified with aapt2 on the APK's `res/v9.xml`:

- Header 72dp; logo 48dp; wordmark 60 × 20dp; model label 11sp.
- Header actions 68 × 32dp, text 11sp, separation 5dp (original had no icons).
- Tab strip 52dp; buttons 38dp; horizontal padding 14dp; vertical padding 7dp;
  image button start margin -4dp.
- Composer padding 12dp horizontally, 7dp top, 11dp bottom. Add and voice
  controls 48 × 52dp; send 64 × 52dp; input minimum height 52dp, max 5 lines;
  input/send start margins 7dp.
- Attachment bar 58dp; preview 42dp; remove 58 × 42dp; attachment text 13sp.

Verified with dexdump in the old message adapter (`r6.getView`):

- Row padding 14dp horizontally and 5dp vertically.
- Bubble padding 14dp horizontally and 11dp vertically; assistant minimum 180dp.
- Body 16sp; line-height multiplier 1.08; text max width 78% of screen.
- Metadata relative text scale 0.72 (11.52sp).

Current additions retained: 40dp avatars and 10dp gap with remaining-width
clamping; speech icon footer; selectable message text and long-press deletion;
both attachment and return-to-voice controls. Header actions are widened to
88dp/80dp to fit their added icons. Input text is explicitly 18sp (the old APK
used the platform default). Tab text is explicitly 14sp. These choices are not
claimed as recovered old measurements.

Four original tab PNGs remain connected through the chat/image selectors.
No chat history is altered by the layout screenshot instrumentation.
