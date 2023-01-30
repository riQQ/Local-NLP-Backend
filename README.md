Local NLP Backend - A Déjà Vu Fork
==================================
This is a backend for [UnifiedNlp](https://github.com/microg/android_packages_apps_UnifiedNlp) that uses locally acquired WLAN/WiFi AP and mobile/cellular tower data to resolve user location. Collectively, “WLAN/WiFi and mobile/cellular” signals will be called “RF emitters” below.

Conceptually, this backend consists of two parts sharing a common database. One part passively monitors the GPS. If the GPS has acquired and has a good position accuracy, then the coverage maps for RF emitters detected by the phone are created and saved.

The other part is the actual location provider which uses the database to estimate location when the GPS is not available.

This backend uses no network data. All data acquired by the phone stays on the phone.

<a href="https://f-droid.org/packages/helium314.localbackend/" target="_blank">
<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="90"/></a>

Modified version
================
This version has several changes compared to *Déjà Vu*, see the [changelog](CHANGELOG.md) starting at 1.2.0-beta.
Aside from upgrades incorporating recent Android versions, an "active mode" was added, which can be used to build up the database at the cost of some battery.
Local NLP Backend is capable of using and importing any database used in *Déjà Vu*, but be aware that you need root privileges to extract the database.

Potential improvements
======================
Local NLP Backend works mostly fine as it is, but there are some areas where it could be improved:
* characteristics for the various different emitters are roughly estimated from various sources on the internet. Fine tuning of the values might improve location accuracy, especially when also considering frequency effects on range.
* make use of bluetooth emitters. Bluetooth has low range and thus a good potential of giving accurate locations, but is difficult to use properly as many bluetooth emitters are mobile.
* make use of [WiFi-RTT](https://developer.android.com/guide/topics/connectivity/wifi-rtt) for distance estimation. This has the potential to vastly improve precision, but works only on a small number of devices.
* determination of position from found emitters is just working "good enough", but not great. A different approach might yield better results.

Requirements on phone
=====================
This is a plug-in for [microG](https://microg.org/) (UnifiedNlp or GmsCore).

Setup on phone
==============
In the NLP Controller app (interface for microG UnifiedNlp) select the "Local NLP Backend". If using GmsCore, you can find in in microG Settings -> Location modules. Tap on backend name for configuration UI.

When enabled, microG will request you grant location permissions to this backend. This is required so that the backend can monitor mobile/cell tower data and so that it can monitor the positions reported by the GPS.

Note: The microG configuration check requires a location from a location backend to indicate that it is setup properly. However this backend will not return a location until it has mapped at least one mobile cell tower or two WLAN/WiFi access points, or data was imported. So it may be necessary to run an app that uses the GPS for a while before this backend will report information to microG. You may wish to also install a different backend to verify microG setup quickly.

Collecting RF Emitter Data
======================
To conserve power the collection process does not actually turn on the GPS. If some other app turns on the GPS, for example a map or navigation app, then the backend will monitor the location and collect RF emitter data.

What is stored in the database
------------------------------
For each RF emitter detected an estimate of its coverage area (center and radius) is saved.

For WLAN/WiFi APs the SSID is also saved for debug purposes. Analysis of the SSIDs detected by the phone can help identify name patterns used on mobile APs. The backend removes records from the database if the RF emitter has a SSID that is associated with WLAN/WiFi APs that are often mobile (e.g. "Joes iPhone").

Clearing the database
---------------------
This software does not have a clear or reset database function built it but you can use settings->Storage>Internal shared storage->Apps->Local NLP Backend->Clear Data to remove the current database.

Permissions Required
====================
|Permission|Use|
|:----------|:---|
ACCESS_COARSE_LOCATION|Allows backend to determine which cell towers your phone detects.
ACCESS_FINE_LOCATION|Allows backend to determine which WiFis your phone detect and monitor position reports from the GPS.
ACCESS_BACKGROUND_LOCATION|Necessary on Android 10 and higher, as the backend only runs in foreground when using active mode.
CHANGE_WIFI_STATE|Allows backend to scan for nearby WiFis.
ACCESS_WIFI_STATE|Allows backend to access WiFi scan results.
FOREGROUND_SERVICE|Needed so GPS can be used in active mode.

Some permissions may not be necessary, this heavily depends on [Android version](https://developer.android.com/guide/topics/connectivity/wifi-scan).

Changes
=======
[Revision history is kept in a separate change log.](CHANGELOG.md)

Credits
=======
The Kalman filter used in this backend is based on [work by @villoren](https://github.com/villoren/KalmanLocationManager.git).

Most of this project is adjusted from [Déjà Vu](https://github.com/n76/DejaVu)

License
=======

Most of this project is licensed by GNU GPL. The Kalman filter code retains its original MIT license.

Icon
----
The icon for this project is derived from:

[Geolocation icon](https://commons.wikimedia.org/wiki/File:Geolocation_-_The_Noun_Project.svg) released under [CC0 license](https://creativecommons.org/publicdomain/zero/1.0/deed.en).

GNU General Public License
--------------------------
Copyright (C) 2017-18 Tod Fitch
Copyright (C) 2022 Helium314

This program is Free Software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License

MIT License
-----------
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
