AT-PEN MAPPING FOR ANDROID

Option A: Install as an app from a web address (no Android Studio)
  1. Upload the contents of the "pwa" folder to any HTTPS host (GitHub Pages, Netlify, Cloudflare Pages).
  2. On the phone open that address in Chrome, allow Location, then menu > Install app (or Add to Home screen).
  GPS needs HTTPS, so it will not work from a file opened off the phone's storage.

Option B: Build an APK
  1. Install Android Studio. Choose File > Open and select the "android" folder.
  2. Let Gradle sync, then Build > Build APK(s). Or plug in the phone with USB debugging and press Run.
  3. The first launch asks for location permission. Exports go to the phone's Downloads folder.
  The app files are bundled inside the APK, so it works with no signal in the field.

MAP FILES
The app saves YARD_MAP.TXT in the same layout as the FI2 example: a comment header (feedyard, location, mapped by, mapped on), then one PEN line per pen with four corners.

SATELLITE VIEW AND GOOGLE EARTH
The Sat button on the map shows Esri World Imagery behind your pens. It needs an internet connection and
credits Esri on screen. Check Esri's terms if you use it commercially.
Save KML (Google Earth) on the Map file tab writes YARD_MAP.kml. Open it in Google Earth to see every pen
outline and bunk line on Google's imagery. This works offline.

UNITS
Distances show in feet by default. Switch to meters under Units on the Pens tab. Map files are not affected.

BLUETOOTH GPS RECEIVER (TOPGNSS TOP608BT AND SIMILAR) - ANDROID APP
1. Turn the receiver on and pair it in Android Settings > Bluetooth (PIN is usually 0000 or 1234).
2. Open AT-Pen Mapping > GPS tab > Position source > "Bluetooth receiver (paired)".
3. Tap "Allow Bluetooth" if asked, then tap Connect next to the receiver.
4. The header shows the fix, satellites and accuracy from the receiver. The app remembers the receiver and reconnects
   by itself next time. Untick "Search or connect automatically" to stop that.
The app reads the receiver's NMEA position (GGA) and error estimate (GST) over Bluetooth serial (SPP).
If the link drops it keeps trying to reconnect every 3 seconds. Rebuild the project in Android Studio to pick up the change.
