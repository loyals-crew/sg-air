# SG Air

A home-screen widget showing the **24-hr PSI** and **1-hr PM2.5** for the part of Singapore you are in. Tap it to see all five regions (North, South, East, West, Central).

Readings come from the National Environment Agency (NEA) via [data.gov.sg](https://data.gov.sg) and are published hourly.

## Download

| Your phone | File to download | What it is |
| --- | --- | --- |
| **Android** (11 or newer) | [SG-Air-Android.apk](https://github.com/loyals-crew/sg-air/raw/main/downloads/SG-Air-Android.apk) | The app, with its widget |
| **iPhone** | [SG-Air-iPhone-Scriptable.js](https://github.com/loyals-crew/sg-air/raw/main/downloads/SG-Air-iPhone-Scriptable.js) | A script for the free Scriptable app |

Both files are in the [`downloads`](downloads) folder. Everything else in this repository is source code, which you do not need in order to use the widget.

## Install on Android

1. Download `SG-Air-Android.apk` on your phone and open it. Android will ask you to allow installing from your browser or file manager.
2. Open **SG Air** and allow location when asked.
3. Long-press the home screen, choose **Widgets**, find **SG Air** and add it.

For the widget to follow you while the app is closed, choose **Allow all the time** for location. Otherwise it updates your region whenever you open the app.

## Install on iPhone

1. Install **Scriptable** from the App Store.
2. Download `SG-Air-iPhone-Scriptable.js` and open it with Scriptable, or create a new script in Scriptable and paste the contents in.
3. Run the script once inside Scriptable and allow location.
4. Long-press the home screen, tap **+**, choose **Scriptable** and add the small widget.
5. Long-press the widget, tap **Edit Widget**, set **Script** to the one you added and **When Interacting** to **Run Script**.

To pin a region or force the appearance, type words into the widget's **Parameter** field: a region (`north`, `south`, `east`, `west`, `central`) and/or `light` or `dark`. For example: `east dark`.

## Good to know

- NEA reports air quality for five regions, so the widget shows the region nearest to you, not a reading for your exact spot.
- Outside Singapore, or before your location is known, it shows the highest reading across Singapore.
- Only an approximate location is used, and it never leaves your phone.
