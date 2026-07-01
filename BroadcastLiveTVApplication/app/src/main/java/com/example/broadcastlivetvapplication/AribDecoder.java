package com.example.broadcastlivetvapplication;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * Dekoduje stringove iz ARIB STD-B24 encodinga u čitljiv Unicode tekst.
 *
 * MW vraća ARIB stringove kao Java String gdje su bajtovi interpretirani
 * kao Latin-1 (svaki char = jedan bajt). ARIB STD-B24 koristi JIS X 0208
 * character set sa 7-bitnim bajtovima (bez high bita). Postavljanjem high
 * bita na svakom bajtu dobijamo EUC-JP encoding koji Java može dekodovati.
 */
final class AribDecoder {

    private static final Charset EUC_JP = Charset.forName("EUC-JP");

    private AribDecoder() {
    }

    /**
     * Dekoduje string iz MW u Unicode.
     *
     * MW vraća dvije vrste stringova:
     * - ARIB STD-B24 (7-bit): svi bajtovi < 0x80, JIS X 0208 bez high bita → dodaj 0x80 i dekoduj kao EUC-JP
     * - Latin/UTF-8 (8-bit): barem jedan bajt >= 0x80, već je čitljiv kao Latin-1
     *
     * @param raw string iz MW (svaki char predstavlja jedan bajt)
     * @return dekodovani Unicode string, ili originalni string ako dekodovanje nije uspjelo
     */
    static String decode(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        byte[] bytes = toLatin1Bytes(raw);
        if (isSevenBitArib(bytes)) {
            return decodeArib7bit(bytes, raw);
        }
        return raw;
    }

    private static boolean isSevenBitArib(byte[] bytes) {
        for (byte b : bytes) {
            if ((b & 0xFF) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    private static String decodeArib7bit(byte[] bytes, String fallback) {
        byte[] eucJp = setHighBits(bytes);
        try {
            return new String(eucJp, "EUC-JP");
        } catch (UnsupportedEncodingException e) {
            return fallback;
        }
    }

    private static byte[] toLatin1Bytes(String s) {
        byte[] bytes = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            bytes[i] = (byte) (s.charAt(i) & 0xFF);
        }
        return bytes;
    }

    private static byte[] setHighBits(byte[] bytes) {
        byte[] result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = (byte) (bytes[i] | 0x80);
        }
        return result;
    }
}
