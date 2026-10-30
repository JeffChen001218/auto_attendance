#!/system/bin/sh
# Magisk late_start service; this script only runs while the module is enabled.
MODDIR=${0%/*}
. "$MODDIR/config.sh"

LOGDIR=/data/adb/codex-autolark
mkdir -p "$LOGDIR"
chmod 0700 "$LOGDIR"
LOGFILE=$LOGDIR/boot.log

log_line() {
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1" >> "$LOGFILE"
}

tries=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$tries" -lt "$BOOT_WAIT_TRIES" ]; do
    sleep 2
    tries=$((tries + 1))
done

if [ "$(getprop sys.boot_completed)" != 1 ]; then
    log_line 'Android boot did not complete; scheduling broadcast skipped'
    exit 1
fi

log_line 'Magisk module service started'

if [ "$AUTO_ENABLE_FAKEWIFI" = 1 ]; then
    VECTOR_CLI=/data/adb/modules/zygisk_vector/cli
    if [ ! -x "$VECTOR_CLI" ]; then
        VECTOR_CLI=/data/adb/lspd/cli
    fi
    if [ -x "$VECTOR_CLI" ]; then
        tries=0
        while ! "$VECTOR_CLI" status > /dev/null 2>&1 \
                && [ "$tries" -lt "$VECTOR_WAIT_TRIES" ]; do
            sleep 2
            tries=$((tries + 1))
        done
        if "$VECTOR_CLI" status > /dev/null 2>&1; then
            if "$VECTOR_CLI" modules ls | awk \
                    '$1 == "com.hook.fakewifi" && $3 == "enabled" { found = 1 } END { exit !found }'; then
                log_line 'FakeWifi already enabled in Vector'
            elif "$VECTOR_CLI" modules enable com.hook.fakewifi >> "$LOGFILE" 2>&1; then
                log_line 'FakeWifi enabled in Vector'
            else
                log_line 'Could not enable FakeWifi in Vector'
            fi
            if "$VECTOR_CLI" scope ls com.hook.fakewifi | awk \
                    '$1 == "com.ss.android.lark" && $2 == "0" { found = 1 } END { exit !found }'; then
                log_line 'Lark already in FakeWifi scope for user 0'
            elif "$VECTOR_CLI" scope add com.hook.fakewifi com.ss.android.lark/0 \
                    >> "$LOGFILE" 2>&1; then
                log_line 'Lark added to FakeWifi scope for user 0'
            else
                log_line 'Could not add Lark to FakeWifi scope'
            fi
        else
            log_line 'Vector daemon unavailable; scope setup skipped'
        fi
    else
        log_line 'Vector CLI not installed; scope setup skipped'
    fi
fi

if su -Z u:r:shell:s0 shell -c \
        'am broadcast --user 0 --include-stopped-packages -a com.codex.autolark.BOOTSTRAP -n com.codex.autolark/.SchedulerReceiver' \
        >> "$LOGFILE" 2>&1; then
    log_line 'Schedule bootstrap broadcast sent'
else
    log_line 'Schedule bootstrap broadcast failed'
fi
