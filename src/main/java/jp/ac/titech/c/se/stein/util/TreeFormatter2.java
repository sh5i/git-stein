/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package jp.ac.titech.c.se.stein.util;

import static org.eclipse.jgit.lib.Constants.OBJECT_ID_LENGTH;
import static org.eclipse.jgit.lib.Constants.OBJ_TREE;
import static org.eclipse.jgit.lib.Constants.encode;

import java.io.IOException;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.util.TemporaryBuffer;

/**
 * A workaround for TreeFormatter in jgit, that accepts int modebits instead of FileMode.
 */
public class TreeFormatter2 {
    public static int entrySize(byte[] mode, int nameLen) {
        return mode.length + nameLen + OBJECT_ID_LENGTH + 2;
    }
    TreeFormatter a;
    private byte[] buf = new byte[8192];

    private int ptr;

    private TemporaryBuffer.Heap overflowBuffer;

    public void append(String name, int mode, AnyObjectId id) {
        byte[] nameBuf = encode(name);
        append(nameBuf, nameBuf.length, parseModeBits(mode), id);
    }

    private void append(byte[] nameBuf, int nameLen, byte[] mode, AnyObjectId id) {
        if (nameLen == 0) {
            throw new IllegalArgumentException(JGitText.get().invalidTreeZeroLengthName);
        }
        if (fmtBuf(nameBuf, nameLen, mode)) {
            id.copyRawTo(buf, ptr);
            ptr += OBJECT_ID_LENGTH;
        } else {
            try {
                fmtOverflowBuffer(nameBuf, nameLen, mode);
                id.copyRawTo(overflowBuffer);
            } catch (IOException badBuffer) {
                throw new RuntimeException(badBuffer);
            }
        }
    }

    private boolean fmtBuf(byte[] nameBuf, int nameLen, byte[] mode) {
        if (buf == null || buf.length < ptr + entrySize(mode, nameLen))
            return false;
        //mode.copyTo(buf, ptr);
        //ptr += mode.copyToLength();
        System.arraycopy(mode, 0, buf, ptr, mode.length);
        ptr += mode.length;
        buf[ptr++] = ' ';
        System.arraycopy(nameBuf, 0, buf, ptr, nameLen);
        ptr += nameLen;
        buf[ptr++] = 0;
        return true;
    }

    private void fmtOverflowBuffer(byte[] nameBuf, int nameLen, byte[] mode) throws IOException {
        if (buf != null) {
            overflowBuffer = new TemporaryBuffer.Heap(Integer.MAX_VALUE);
            overflowBuffer.write(buf, 0, ptr);
            buf = null;
        }
        //mode.copyTo(overflowBuffer);
        overflowBuffer.write(mode);
        overflowBuffer.write((byte) ' ');
        overflowBuffer.write(nameBuf, 0, nameLen);
        overflowBuffer.write((byte) 0);
    }

    public ObjectId insertTo(ObjectInserter ins) throws IOException {
        if (buf != null)
            return ins.insert(OBJ_TREE, buf, 0, ptr);
        final long len = overflowBuffer.length();
        return ins.insert(OBJ_TREE, len, overflowBuffer.openInputStream());
    }

    public ObjectId computeId(ObjectInserter ins) {
        if (buf != null)
            return ins.idFor(OBJ_TREE, buf, 0, ptr);
        final long len = overflowBuffer.length();
        try {
            return ins.idFor(OBJ_TREE, len, overflowBuffer.openInputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Variant of FileMode#FileMode.
     */
    public static byte[] parseModeBits(int mode) {
        if (mode != 0) {
            final byte[] tmp = new byte[10];
            int p = tmp.length;
            while (mode != 0) {
                tmp[--p] = (byte) ('0' + (mode & 07));
                mode >>= 3;
            }
            byte[] result = new byte[tmp.length - p];
            System.arraycopy(tmp, p, result, 0, result.length);
            return result;
        } else {
            return new byte[] { '0' };
        }
    }
}
