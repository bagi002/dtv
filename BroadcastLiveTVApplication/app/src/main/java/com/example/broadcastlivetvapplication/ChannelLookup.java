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

    /** Cita ONID/TSID/serviceId iz TvContract.Channels za channelUri, zatim trazi odgovarajuci servis preko ServiceControl. */
    @Nullable
    static Result findServiceIndex(ContentResolver contentResolver, ServiceControl serviceControl, Uri channelUri) {
        int[] triplet = readTriplet(contentResolver, channelUri);
        if (triplet == null) {
            Log.e(TAG, "findServiceIndex: no TvContract row for " + channelUri);
            return null;
        }
        int onid = triplet[0];
        int tsid = triplet[1];
        int serviceId = triplet[2];

        int count = serviceControl.getServiceListCount(SERVICE_LIST_INDEX);
        for (int i = 0; i < count; i++) {
            ServiceDescriptor descriptor = serviceControl.getServiceDescriptor(SERVICE_LIST_INDEX, i);
            if (descriptor != null && descriptor.getONID() == onid && descriptor.getTSID() == tsid
                    && descriptor.getServiceId() == serviceId) {
                return new Result(SERVICE_LIST_INDEX, i, onid, tsid, serviceId);
            }
        }
        Log.e(TAG, "findServiceIndex: no ServiceDescriptor match for onid=" + onid + " tsid=" + tsid
                + " serviceId=" + serviceId);
        return null;
    }

    @Nullable
    private static int[] readTriplet(ContentResolver contentResolver, Uri channelUri) {
        String[] projection = {
                TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
                TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
                TvContract.Channels.COLUMN_SERVICE_ID,
        };
        try (Cursor cursor = contentResolver.query(channelUri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            return new int[] { cursor.getInt(0), cursor.getInt(1), cursor.getInt(2) };
        }
    }
}
