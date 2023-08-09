package jp.ac.titech.c.se.stein.util;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.sha1.SHA1;

import java.nio.charset.StandardCharsets;

@Slf4j
public class HashUtils {
    public static String digest(final String name, final int length) {
        final SHA1 sha1 = SHA1.newInstance();
        sha1.update(name.getBytes(StandardCharsets.UTF_8));
        return ObjectId.fromRaw(sha1.digest()).abbreviate(length).name();
    }
}
