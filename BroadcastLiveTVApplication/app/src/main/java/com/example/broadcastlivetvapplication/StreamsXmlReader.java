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
 * Parsira streams.xml (<channel><input>url</input></channel> elementi) i vraca <input> vrednosti.
 * Izvor moze biti bundlovan Android resurs (res/xml/streams.xml, fallback) ili fajl na uredjaju
 * cija putanju korisnik unese u SetupActivity — tako lista kanala za skeniranje nije hard-kodovana.
 */
final class StreamsXmlReader {

    private static final String TAG = "StreamsXmlReader";

    private StreamsXmlReader() {
    }

    /** Cita streams.xml iz aplikacionog resursa (res/xml/streams.xml) — koristi se kao fallback. */
    static String[] readScanSourceUrls(Context context) {
        List<String> urls = new ArrayList<>();
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.streams)) {
            parseInputTags(parser, urls);
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Failed to parse bundled streams.xml", e);
        }
        return urls.toArray(new String[0]);
    }

    /** Cita streams.xml sa filesystem putanje na uredjaju (npr. /data/streams.xml). */
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
