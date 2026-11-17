// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Validation, scaling, quantisation and metadata stripping of uploaded flags. */
class FlagImageTest {

    private static final FlagImage.Limits LIMITS = FlagImage.Limits.DEFAULT;

    static byte[] image(final int w, final int h, final String format, final java.util.function.IntBinaryOperator pixel) throws Exception {
        final BufferedImage img = new BufferedImage(w, h, format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, pixel.applyAsInt(x, y));
            }
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, out));
        return out.toByteArray();
    }

    /** Inserts a PNG chunk right after IHDR. */
    static byte[] withChunk(final byte[] png, final String type, final byte[] data) {
        final int ihdrEnd = 8 + 12 + 13;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, ihdrEnd);
        final byte[] len = {(byte) (data.length >>> 24), (byte) (data.length >>> 16), (byte) (data.length >>> 8), (byte) data.length};
        out.writeBytes(len);
        final byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        final CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        final long c = crc.getValue();
        out.writeBytes(new byte[] {(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
        out.write(png, ihdrEnd, png.length - ihdrEnd);
        return out.toByteArray();
    }

    @Test
    void pngIsScaledToTextureSizeAndStrippedOfMetadata() throws Exception {
        byte[] png = image(320, 200, "png", (x, y) -> x < 160 ? 0xFFC0392B : 0xFF2E86C1);
        png = withChunk(png, "tEXt", "Comment\0tajny autor GPS 50.06,19.94".getBytes(StandardCharsets.ISO_8859_1));
        png = withChunk(png, "eXIf", "MM\0*fake-exif".getBytes(StandardCharsets.ISO_8859_1));
        final FlagImage.Result r = FlagImage.normalize(png, LIMITS);
        assertEquals("PNG", r.format());
        assertEquals(320, r.sourceWidth());
        assertEquals(200, r.sourceHeight());
        assertEquals(FlagImage.WIDTH * FlagImage.HEIGHT, r.argb().length);
        final List<String> chunks = FlagImage.pngChunks(r.png());
        assertTrue(Set.of("IHDR", "IDAT", "IEND").containsAll(chunks), chunks.toString());
        final String text = new String(r.png(), StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("tajny"));
        assertFalse(text.contains("exif"));
        final int[] back = FlagImage.decodeStored(r.png());
        assertArrayEquals(r.argb(), back);
        assertEquals(16, r.imageId().length());
        assertEquals(r.sha256(), FlagImage.sha256(r.png()));
        // left half red, right half blue survives the area average (aspect 1.6 = no crop)
        assertEquals(0xFFC0392B, r.argb()[10 * 64 + 5]);
        assertEquals(0xFF2E86C1, r.argb()[10 * 64 + 60]);
    }

    @Test
    void jpegIsAcceptedAndExifDropped() throws Exception {
        final byte[] jpg = image(200, 125, "jpg", (x, y) -> 0xFF1E8449);
        // APP1 Exif segment right after SOI
        final byte[] exif = "Exif\0\0MM\0*secret-camera".getBytes(StandardCharsets.ISO_8859_1);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write((exif.length + 2) >> 8);
        out.write((exif.length + 2) & 0xFF);
        out.writeBytes(exif);
        out.write(jpg, 2, jpg.length - 2);
        final FlagImage.Result r = FlagImage.normalize(out.toByteArray(), LIMITS);
        assertEquals("JPEG", r.format());
        assertFalse(new String(r.png(), StandardCharsets.ISO_8859_1).contains("secret"));
        assertTrue(Set.of("IHDR", "IDAT", "IEND").containsAll(FlagImage.pngChunks(r.png())));
    }

    @Test
    void otherFormatsAndGarbageAreRejected() throws Exception {
        final byte[] gif = image(64, 40, "gif", (x, y) -> 0xFF000000);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlagImage.normalize(gif, LIMITS)).getMessage().contains("PNG i JPG"));
        final byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> FlagImage.normalize(svg, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> FlagImage.normalize(new byte[0], LIMITS));
        final byte[] png = image(64, 40, "png", (x, y) -> 0xFFFFFFFF);
        final byte[] truncated = Arrays.copyOf(png, 40);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlagImage.normalize(truncated, LIMITS)).getMessage()
            .startsWith("Nie udało się odczytać"));
    }

    @Test
    void sizeLimitsAreEnforcedBeforeDecoding() throws Exception {
        final byte[] big = new byte[LIMITS.maxBytes() + 1];
        big[0] = (byte) 0x89;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlagImage.normalize(big, LIMITS)).getMessage().contains("za duży"));
        final byte[] tiny = image(8, 8, "png", (x, y) -> 0xFF000000);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlagImage.normalize(tiny, LIMITS)).getMessage().contains("za mały"));
        // decompression bomb: a PNG header claiming 30000×30000 (only IHDR + IEND, no pixel data)
        final byte[] header = image(16, 10, "png", (x, y) -> 0xFF000000);
        final byte[] bomb = Arrays.copyOf(header, 33);
        bomb[16] = 0;
        bomb[17] = 0;
        bomb[18] = (byte) (30000 >> 8);
        bomb[19] = (byte) (30000 & 0xFF);
        bomb[20] = 0;
        bomb[21] = 0;
        bomb[22] = (byte) (30000 >> 8);
        bomb[23] = (byte) (30000 & 0xFF);
        final CRC32 crc = new CRC32();
        crc.update(bomb, 12, 17);
        final long c = crc.getValue();
        bomb[29] = (byte) (c >>> 24);
        bomb[30] = (byte) (c >>> 16);
        bomb[31] = (byte) (c >>> 8);
        bomb[32] = (byte) c;
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FlagImage.normalize(bomb, LIMITS));
        assertTrue(e.getMessage().contains("za dużą rozdzielczość"), e.getMessage());
    }

    @Test
    void wideImagesAreCentreCropped() throws Exception {
        // 400×100: a 160-wide crop in the middle; the yellow side bands must disappear
        final byte[] png = image(400, 100, "png", (x, y) -> x < 100 || x >= 300 ? 0xFFFFFF00 : 0xFF000080);
        final FlagImage.Result r = FlagImage.normalize(png, LIMITS);
        for (final int p : r.argb()) {
            assertEquals(0xFF000080, p);
        }
        assertEquals(1, r.colors());
    }

    @Test
    void quantisationLimitsColoursDeterministically() throws Exception {
        final byte[] png = image(256, 160, "png", (x, y) -> 0xFF000000 | (x & 0xFF) << 16 | (y * 255 / 159) << 8 | ((x + y) & 0xFF));
        final FlagImage.Result a = FlagImage.normalize(png, LIMITS);
        final FlagImage.Result b = FlagImage.normalize(png, LIMITS);
        assertTrue(a.colors() <= 32, "colors " + a.colors());
        assertTrue(a.colors() >= 16, "colors " + a.colors());
        assertEquals(a.sha256(), b.sha256());
        final FlagImage.Result few = FlagImage.normalize(png, new FlagImage.Limits(160 * 1024, 4 << 20, 4096, 16, 10, 4));
        assertTrue(few.colors() <= 4);
    }

    @Test
    void transparencyBecomesBinaryAndMostlyEmptyFlagsAreRefused() throws Exception {
        // swallowtail: transparent triangle on the fly side
        final byte[] png = image(64, 40, "png", (x, y) -> x > 48 && Math.abs(y - 20) < (x - 48) ? 0x00000000 : 0xE0AA0000);
        final FlagImage.Result r = FlagImage.normalize(png, LIMITS);
        for (final int p : r.argb()) {
            assertTrue(p >>> 24 == 0 || p >>> 24 == 255);
        }
        assertEquals(0, r.argb()[20 * 64 + 63] >>> 24);
        assertEquals(255, r.argb()[20 * 64 + 10] >>> 24);
        final byte[] empty = image(64, 40, "png", (x, y) -> x < 10 ? 0xFF000000 : 0x00000000);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlagImage.normalize(empty, LIMITS)).getMessage()
            .contains("nieprzezroczysta"));
    }

    @Test
    void defaultFlagIsTheTintedDefaultTexture() {
        final int[] tex = FlagImage.defaultTexture();
        final int[] red = FlagImage.defaultFlag(0xB02E26);
        assertEquals(tex.length, red.length);
        // pure white field pixel becomes exactly the country colour
        int whites = 0;
        for (int i = 0; i < tex.length; i++) {
            if ((tex[i] & 0xFFFFFF) == 0xFFFFFF) {
                whites++;
                assertEquals(0xFFB02E26, red[i]);
            }
        }
        assertTrue(whites > tex.length / 4);
        // the motif is a darker shade of the same colour
        final int border = red[0];
        assertTrue(((border >> 16) & 0xFF) < 0xB0 && (border & 0xFF) < 0x26 + 1);
    }

    @Test
    void scalingAveragesInPremultipliedAlpha() {
        // 2×1 source: opaque white + fully transparent red → one pixel must stay white (no red bleed)
        final int[] out = FlagImage.scaleCover(new int[] {0xFFFFFFFF, 0x00FF0000}, 2, 1, 1, 1);
        assertEquals(0xFFFFFF, out[0] & 0xFFFFFF);
        assertEquals(128, out[0] >>> 24, 1);
    }
}
