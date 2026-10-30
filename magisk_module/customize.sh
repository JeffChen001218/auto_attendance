#!/system/bin/sh
# Sourced by Magisk's module installer after default extraction.
if [ "$API" -lt 26 ]; then
    abort 'Timed Check-in requires Android 8.0 or newer'
fi

for apk in \
    "$MODPATH/system/app/AutoLark/AutoLark.apk" \
    "$MODPATH/system/app/FakeWifi/FakeWifi.apk"; do
    [ -s "$apk" ] || abort "Missing APK: $apk"
    set_perm "$apk" 0 0 0644
done

set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/config.sh" 0 0 0644
ui_print '- Timed Check-in and FakeWifi APKs bundled as system apps'
ui_print '- Vector must be installed and Zygisk enabled for FakeWifi hooking'
ui_print '- Grant root to Timed Check-in after the first reboot'
