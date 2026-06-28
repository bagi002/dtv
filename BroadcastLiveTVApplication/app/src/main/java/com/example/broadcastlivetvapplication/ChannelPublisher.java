package com.example.broadcastlivetvapplication;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.iwedia.dtv.service.ServiceDescriptor;

import java.util.ArrayList;
import java.util.List;

import iwedia.dtv.service.ServiceControl;

/**
 * Akumulira servise svakog skeniranog izvora (native file/IP install drzi samo poslednji multipleks),
 * pa ih sve upisuje u TvContract.Channels sa URL-om izvora — koji zapping koristi za re-scan pri tune-u.
 */
final class ChannelPublisher {

    private static final String TAG = "ChannelPublisher";
    private static final int SERVICE_LIST_INDEX = 0;

    /** Snapshot jednog servisa iz middleware liste + iz kog .ts izvora dolazi. */
    static final class ScannedService {
        final int onid;
        final int tsid;
        final int serviceId;
        final String name;
        final String sourceUrl;

        ScannedService(int onid, int tsid, int serviceId, String name, String sourceUrl) {
            this.onid = onid;
            this.tsid = tsid;
            this.serviceId = serviceId;
            this.name = name;
            this.sourceUrl = sourceUrl;
        }

        /** Jedinstven kljuc kanala: triplet + izvor (isti triplet iz razlicitih fajlova = razliciti kanali, npr. ch0 vs clear). */
        String providerId() {
            return onid + "_" + tsid + "_" + serviceId + "_" + Integer.toHexString(sourceUrl.hashCode());
        }
    }

    private final ContentResolver mContentResolver;
    private final ServiceControl mServiceControl;
    private final List<ScannedService> mAccumulated = new ArrayList<>();

    ChannelPublisher(ContentResolver contentResolver, ServiceControl serviceControl) {
        mContentResolver = contentResolver;
        mServiceControl = serviceControl;
    }

    /** Procita trenutnu middleware listu (servisi upravo skeniranog izvora) i akumulira ih sa njihovim sourceUrl. */
    void collectScannedServices(String sourceUrl) {
        // DIJAGNOSTIKA: koliko lista middleware drzi i sta je u svakoj (proba hipoteze da su multipleksi u zasebnim listama).
        int numLists = mServiceControl.getNumberOfServiceLists();
        Log.d(TAG, "DIAG after " + sourceUrl + ": getNumberOfServiceLists()=" + numLists);
        for (int li = 0; li < numLists; li++) {
            int lc = mServiceControl.getServiceListCount(li);
            String listName = mServiceControl.getServiceListName(li);
            Log.d(TAG, "DIAG   list[" + li + "] name='" + listName + "' count=" + lc);
            for (int si = 0; si < lc; si++) {
                ServiceDescriptor d = mServiceControl.getServiceDescriptor(li, si);
                if (d != null) {
                    Log.d(TAG, "DIAG      list[" + li + "][" + si + "] sid=" + d.getServiceId()
                            + " onid=" + d.getONID() + " tsid=" + d.getTSID() + " name=" + d.getName());
                }
            }
        }

        int count = mServiceControl.getServiceListCount(SERVICE_LIST_INDEX);
        Log.d(TAG, "collectScannedServices: source=" + sourceUrl + " getServiceListCount(0)=" + count);
        for (int i = 0; i < count; i++) {
            ServiceDescriptor descriptor = mServiceControl.getServiceDescriptor(SERVICE_LIST_INDEX, i);
            if (descriptor == null) {
                Log.w(TAG, "collectScannedServices: i=" + i + " descriptor=null");
                continue;
            }
            ScannedService s = new ScannedService(descriptor.getONID(), descriptor.getTSID(),
                    descriptor.getServiceId(), descriptor.getName(), sourceUrl);
            mAccumulated.add(s);
            Log.d(TAG, "collectScannedServices: i=" + i + " serviceId=" + s.serviceId
                    + " onid=" + s.onid + " tsid=" + s.tsid + " name=" + s.name);
        }
    }

    /** Upisuje sve akumulirane servise za dati inputId; vraca broj upisanih kanala. */
    int publishAll(String inputId) {
        if (inputId == null) {
            return 0;
        }
        for (ScannedService s : mAccumulated) {
            upsertChannel(inputId, s);
        }
        Log.d(TAG, "publishAll: upisano " + mAccumulated.size() + " kanala");
        return mAccumulated.size();
    }

    private void upsertChannel(String inputId, ScannedService s) {
        String providerId = s.providerId();

        ContentValues values = new ContentValues();
        values.put(TvContract.Channels.COLUMN_INPUT_ID, inputId);
        values.put(TvContract.Channels.COLUMN_DISPLAY_NAME, s.name);
        values.put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, String.valueOf(s.serviceId));
        values.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_DVB_T);
        values.put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO);
        values.put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, s.onid);
        values.put(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID, s.tsid);
        values.put(TvContract.Channels.COLUMN_SERVICE_ID, s.serviceId);
        values.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, providerId);
        // sourceUrl: koji .ts re-skenirati pri zappingu (middleware drzi samo jedan multipleks).
        values.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, s.sourceUrl.getBytes());

        Long existingChannelId = findChannelId(inputId, providerId);
        if (existingChannelId != null) {
            mContentResolver.update(TvContract.buildChannelUri(existingChannelId), values, null, null);
        } else {
            mContentResolver.insert(TvContract.Channels.CONTENT_URI, values);
        }
    }

    @Nullable
    private Long findChannelId(String inputId, String providerId) {
        Uri uri = TvContract.buildChannelsUriForInput(inputId);
        String[] projection = {
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
        };
        try (Cursor cursor = mContentResolver.query(uri, projection, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                if (providerId.equals(cursor.getString(1))) {
                    return cursor.getLong(0);
                }
            }
        }
        return null;
    }
}
