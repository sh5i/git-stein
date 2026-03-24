package jp.ac.titech.c.se.stein.util;

import picocli.CommandLine.ITypeConverter;

/**
 * Converts a human-readable size string (e.g., "10", "1K", "256M", "1.5G") to bytes.
 */
public class SizeConverter implements ITypeConverter<Long> {
    @Override
    public Long convert(final String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty value is given");
        }
        final int len = value.length();
        final char unit = Character.toUpperCase(value.charAt(len - 1));
        final String num = value.substring(0, len - 1);
        return switch (unit) {
            case 'B' -> convert(num);
            case 'K' -> displaySizeToByteCount(num, 1024);
            case 'M' -> displaySizeToByteCount(num, 1024 * 1024);
            case 'G' -> displaySizeToByteCount(num, 1024 * 1024 * 1024);
            default -> displaySizeToByteCount(value, 1);
        };
    }

    protected long displaySizeToByteCount(final String value, final long base) {
        if (value.contains(".")) {
            return (long) (Double.parseDouble(value) * base);
        } else {
            return Long.parseLong(value) * base;
        }
    }
}
