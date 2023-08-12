package jp.ac.titech.c.se.stein.util;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.sha1.SHA1;

import java.nio.charset.StandardCharsets;

@Slf4j
public class HashUtils {
    public static String digest(final byte[] data, final int length) {
        final SHA1 sha1 = SHA1.newInstance();
        sha1.update(data);
        return ObjectId.fromRaw(sha1.digest()).abbreviate(length).name();
    }

    public static String digest(final byte[] data) {
        final SHA1 sha1 = SHA1.newInstance();
        sha1.update(data);
        return ObjectId.fromRaw(sha1.digest()).name();
    }

    public static String digest(final String data, final int length) {
        return digest(data.getBytes(StandardCharsets.UTF_8), length);
    }

    public static String digest(final String data) {
        return digest(data.getBytes(StandardCharsets.UTF_8));
    }
}
