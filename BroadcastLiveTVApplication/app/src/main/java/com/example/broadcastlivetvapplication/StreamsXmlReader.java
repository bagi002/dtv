package com.example.broadcastlivetvapplication;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser za streams.xml format ({@code <channel><input>url</input></channel>}).
 * Podrzava dva izvora: bundlovani Android resurs ({@code res/xml/streams.xml})
 * koji sluzi kao fallback, i fajl na filesystem putanji koji korisnik unese u
 * SetupActivity — tako lista kanala za skeniranje nije hard-kodovana u APK-u.
 */
final class StreamsXmlReader {

    private static final String TAG = "StreamsXmlReader";

    private StreamsXmlReader() {
    }

    /**
     * Cita listu URL-ova iz bundlovanog {@code res/xml/streams.xml} resursa.
     * Koristi se kao fallback kada korisnik ne unese putanju u SetupActivity.
     *
     * @param context Android context za pristup resursima
     * @return niz URL-ova {@code <input>} elemenata; prazan niz ako parsiranje ne uspe
     */
    static String[] readScanSourceUrls(Context context) {
        List<String> urls = new ArrayList<>();
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.streams)) {
            parseInputTags(parser, urls);
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Failed to parse bundled streams.xml", e);
        }
        return urls.toArray(new String[0]);
    }

    /**
     * Cita listu URL-ova iz streams.xml fajla na zadatoj filesystem putanji.
     *
     * @param filePath apsolutna putanja do fajla na uredjaju (npr. {@code /data/streams.xml})
     * @return niz URL-ova {@code <input>} elemenata; prazan niz ako fajl ne postoji ili parsiranje ne uspe
     */
    static String[] readScanSourceUrlsFromFile(String filePath) {
        List<String> urls = new ArrayList<>();
        try (Reader reader = new FileReader(filePath)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(reader);
            parseInputTags(parser, urls);
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Failed to parse streams.xml at " + filePath, e);
        }
        return urls.toArray(new String[0]);
    }

    /**
     * Prolazi kroz XML dogadjaje i skuplja tekstualni sadrzaj svih {@code <input>} elemenata.
     *
     * @param parser inicijalizovan XmlPullParser pozicioniran na pocetak dokumenta
     * @param urls   lista u koju se dodaju pronadjeni URL-ovi
     * @throws XmlPullParserException ako XML nije ispravan
     * @throws IOException            ako citanje fajla ne uspe
     */
    private static void parseInputTags(XmlPullParser parser, List<String> urls)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        boolean insideInput = false;
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && "input".equals(parser.getName())) {
                insideInput = true;
            } else if (eventType == XmlPullParser.TEXT && insideInput) {
                String text = parser.getText();
                if (text != null && !text.trim().isEmpty()) {
                    urls.add(text.trim());
                }
            } else if (eventType == XmlPullParser.END_TAG && "input".equals(parser.getName())) {
                insideInput = false;
            }
            eventType = parser.next();
        }
    }
}
