# RPi Camera Viewer

This program plays the raw H.264 video from a Raspberry Pi.

## Copyright and License

Copyright &copy; 2016 Shawn Baker using the [MIT License](https://opensource.org/licenses/MIT).

Raspberry image by [Martin Bérubé](http://www.how-to-draw-funny-cartoons.com),
camera image by [Oxygen Team](http://www.oxygen-icons.org).

## Instructions

[RPi Camera Viewer for Android](http://frozen.ca/rpi-camera-viewer-for-android)

[Streaming Raw H.264 From A Raspberry Pi](http://frozen.ca/streaming-raw-h-264-from-a-raspberry-pi)

## Importing opencv Dependency in Android Studio
1.) > File > Project Structure > Choose "app" in Modules on left panel > Dependencies > + > openCV
2.) There are a few steps to integrating NDK. The Android Tutorial shows them clearly:
 https://codelabs.developers.google.com/codelabs/android-studio-jni/index.html?index=..%2F..%2Findex#1
- Steps of Note:
	NDK not downloaded by default in Android Studio. > Tools > Android > SDK Manager > SDK Tools > NDK (select it) 
