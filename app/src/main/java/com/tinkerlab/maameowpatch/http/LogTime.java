package com.tinkerlab.maameowpatch.http;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 日志时间格式（纯 JVM）。 */
public final class LogTime {

    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";

    private LogTime() {
    }

    /** 设备默认时区。 */
    public static String format(long epochMs) {
        SimpleDateFormat sdf = new SimpleDateFormat(PATTERN, Locale.US);
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(epochMs));
    }

    public static String now() {
        return format(System.currentTimeMillis());
    }
}
