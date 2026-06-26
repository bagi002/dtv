package com.example.broadcastlivetvapplication;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Parsira res/xml/streams.xml i vraca <input> vrednosti svih <channel> elemenata (vidi UML "Tuner SDK/HAL - demonstration"). */
final class StreamsXmlReader {

    private static final String TAG = "StreamsXmlReader";

    private StreamsXmlReader() {
    }

    static String[] readScanSourceUrls(Context context) {
        List<String> urls = new ArrayList<>();
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.streams)) {
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
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Failed to parse streams.xml", e);
        }
        return urls.toArray(new String[0]);
    }
}
