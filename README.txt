TV Home - your HTML design as a real Android TV launcher

1. Open this folder in Android Studio (File > Open > select "TvHome").
2. Let it sync. If it asks to install SDK platform 34 or to upgrade the Android Gradle plugin, say yes.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. The file appears at app/build/outputs/apk/debug/app-debug.apk
5. Install on the TV:   adb install -r app-debug.apk
6. Test it first:       adb shell am start -n com.example.tvhome/.MainActivity
7. Only when it works:  adb shell pm disable-user --user 0 com.google.android.tvlauncher
   Undo anytime:        adb shell pm enable com.google.android.tvlauncher

The design lives in app/src/main/assets/index.html
