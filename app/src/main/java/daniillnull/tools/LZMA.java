/*
 * Decompiled with CFR 0.152.
 */
package daniillnull.tools;

import SevenZip.Compression.LZMA.Decoder;
import SevenZip.Compression.LZMA.Encoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;

public class LZMA {
    public static byte[] decompress(byte[] data) throws DataFormatException {
        ByteArrayInputStream is = new ByteArrayInputStream(data);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        Decoder d = new Decoder();
        long len = 0L;
        try {
            byte[] props = new byte[5];
            if (is.read(props) != props.length) {
                throw new RuntimeException();
            }
            if (!d.SetDecoderProperties(props)) {
                throw new RuntimeException();
            }
            for (int i = 0; i < 4; ++i) {
                int v = is.read();
                if (v < 0) {
                    throw new RuntimeException();
                }
                len |= ((long)v & 0xFFL) << (i * 8);
            }
            if (!d.Code(is, os, len)) {
                throw new RuntimeException();
            }
            return os.toByteArray();
        } catch (Exception e) {
            throw new DataFormatException(e.getMessage());
        }
    }
}
