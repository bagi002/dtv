package com.example.broadcastlivetvapplication;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.iwedia.dtv.service.ServiceDescriptor;

import iwedia.dtv.service.ServiceControl;

/** Inverzno od ChannelPublisher: za dati TvContract channelUri nalazi listIndex/serviceIndex u liba4tv Service & Mux DB. */
final class ChannelLookup {

    private static final String TAG = "ChannelLookup";
    private static final int SERVICE_LIST_INDEX = 0;

    private ChannelLookup() {
    }

    static final class Result {
        final int listIndex;
        final int serviceIndex;
        final int onid;
        final int tsid;
        final int serviceId;

        Result(int listIndex, int serviceIndex, int onid, int tsid, int serviceId) {
            this.listIndex = listIndex;
            this.serviceIndex = serviceIndex;
            this.onid = onid;
            this.tsid = tsid;
            this.serviceId = serviceId;
        }
    }

    /** Triplet + sourceUrl procitani iz TvContract reda za jedan kanal (pre trazenja u middleware listi). */
    private static final class ChannelInfo {
        final int onid;
        final int tsid;
        final int serviceId;
        final String sourceUrl;

        ChannelInfo(int onid, int tsid, int serviceId, String sourceUrl) {
            this.onid = onid;
            this.tsid = tsid;
            this.serviceId = serviceId;
            this.sourceUrl = sourceUrl;
        }
    }

    /** Cita .ts izvor kanala iz TvContract-a; null ako kanal ne postoji ili nema upisan izvor. */
    @Nullable
    static String readSourceUrl(ContentResolver contentResolver, Uri channelUri) {
        ChannelInfo info = readChannelInfo(contentResolver, channelUri);
        return info != null ? info.sourceUrl : null;
    }

    /** Cita ONID/TSID/serviceId iz TvContract.Channels za channelUri, zatim trazi odgovarajuci servis preko ServiceControl.
     *  Vraca null ako servis trenutno nije u middleware listi — pozivac treba da uradi re-scan i probni ponovo. */
    @Nullable
    static Result findServiceIndex(ContentResolver contentResolver, ServiceControl serviceControl, Uri channelUri) {
        ChannelInfo info = readChannelInfo(contentResolver, channelUri);
        if (info == null) {
            Log.e(TAG, "findServiceIndex: no TvContract row for " + channelUri);
            return null;
        }

        int count = serviceControl.getServiceListCount(SERVICE_LIST_INDEX);
        for (int i = 0; i < count; i++) {
            ServiceDescriptor descriptor = serviceControl.getServiceDescriptor(SERVICE_LIST_INDEX, i);
            if (descriptor != null && descriptor.getONID() == info.onid && descriptor.getTSID() == info.tsid
                    && descriptor.getServiceId() == info.serviceId) {
                return new Result(SERVICE_LIST_INDEX, i, info.onid, info.tsid, info.serviceId);
            }
        }
        Log.w(TAG, "findServiceIndex: no ServiceDescriptor match for onid=" + info.onid + " tsid=" + info.tsid
                + " serviceId=" + info.serviceId + " (multipleks verovatno nije trenutno instaliran)");
        return null;
    }

    @Nullable
    private static ChannelInfo readChannelInfo(ContentResolver contentResolver, Uri channelUri) {
        // TvProvider ignorise redosled iz projection-a i vraca kolone u svom internom redosledu,
        // pa se moraju citati po imenu (getColumnIndexOrThrow), ne po fiksnoj poziciji.
        String[] projection = {
                TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
                TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
                TvContract.Channels.COLUMN_SERVICE_ID,
                TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA,
        };
        try (Cursor cursor = contentResolver.query(channelUri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int onid = cursor.getInt(cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID));
            int tsid = cursor.getInt(cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID));
            int serviceId = cursor.getInt(cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_SERVICE_ID));
            byte[] sourceUrlBytes = cursor.getBlob(
                    cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA));
            String sourceUrl = sourceUrlBytes != null ? new String(sourceUrlBytes) : null;
            return new ChannelInfo(onid, tsid, serviceId, sourceUrl);
        }
    }
}
