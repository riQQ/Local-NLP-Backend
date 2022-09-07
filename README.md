Local NLP Backend - A Déjà Vu Fork
==================================
This is a backend for [UnifiedNlp](https://github.com/microg/android_packages_apps_UnifiedNlp) that uses locally acquired WLAN/WiFi AP and mobile/cellular tower data to resolve user location. Collectively, “WLAN/WiFi and mobile/cellular” signals will be called “RF emitters” below.

Conceptually, this backend consists of two parts sharing a common database. One part passively monitors the GPS. If the GPS has acquired and has a good position accuracy, then the coverage maps for RF emitters detected by the phone are created and saved.

The other part is the actual location provider which uses the database to estimate location when the GPS is not available.

This backend uses no network data. All data acquired by the phone stays on the phone.

<!--
<a href="https://f-droid.org/packages/org.fitchfamily.android.dejavu/" target="_blank">
<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="90"/></a>
-->

Modified version
================
This version has several changes compared to *Déjà Vu*, see the [changelog](CHANGELOG.md) starting at 1.2.0-beta.
Local NLP Backend is capable of using and importing any database used in *Déjà Vu*, but be aware that you need root privileges to extract the database.

Potential future improvements:
* Improve method for determining which emitters to discard in case of conflicting position report
* Upgrade to more recent Android API. This means some old method for detecting mobile cells will be removed, and may break cell detection on some old devices.
* Enable detecting 5G emitters and maybe other types (API upgrade necessary).
* *Active mode* for automatically enabling GPS to determine position of newly found emitters.
* Find and fix potential bugs

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
CHANGE_WIFI_STATE|Allows backend to scan for nearby WiFis.
ACCESS_WIFI_STATE|Allows backend to access WiFi scan results.

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
