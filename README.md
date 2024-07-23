USB Video
================

USB Video is an open source Android project that provides video and audio streaming library and sample apps on Android and Meta Quest devices from gaming consoles, phones, and other HDMI out sources using a USB Video Class (UVC) compatible USB Video Capture Card accessory.

Features
--------

* Video and audio capture and streaming engine
* Compatible with Android and Meta Quest devices
* Supports gaming consoles, phones, and other HDMI out sources
* Uses a USB Video Class (UVC) compatible USB Video Capture Card accessory

Getting Started
---------------

To get started with USB Video, follow these steps:

1. Clone the repository:
```bash
git clone https://github.com/facebookexperimental/usb-video.git
cd usb-video
```
2. Open the project in Android Studio:
```bash
studio .
```
3. Build the project:
```bash
./gradlew build
```
4. Install the app on your device:
```bash
./gradlew installDebug
```
5. A USB video capture card is required. Use USB 3.0 (SuperSpeed) card and cables. The app has been tested on Quest 2 and Quest 3. 

Contributing
------------
See the [CONTRIBUTING](CONTRIBUTING.md) file for how to help out.

## License
usb-video is [Apache 2.0 licensed](LICENSE).
