package com.example.broadcastlivetvapplication;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;
import android.util.SparseArray;

import com.iwedia.dtv.service.ServiceDescriptor;

import iwedia.dtv.service.ServiceControl;

/**
 * Gradi mapu serviceIndex → TvContract channelId čitanjem TvProvider baze i
 * MW liste servisa, i sparivanjem DVB tripleta (ONID, TSID, SID).
 */
final class EpgChannelMapper {

    private static final String TAG = "EpgChannelMapper";
    private static final int SERVICE_LIST_INDEX = 0;

    private final ContentResolver mContentResolver;
    private final ServiceControl mServiceControl;

    EpgChannelMapper(ContentResolver contentResolver, ServiceControl serviceControl) {
        mContentResolver = contentResolver;
        mServiceControl = serviceControl;
    }

    /**
     * Čita MW listu servisa i TvProvider kanale, pa spaja po DVB tripletu.
     *
     * @param inputId TIF input ID
     * @return mapa serviceIndex → channelId (samo za servise koji imaju odgovarajući kanal)
     */
    SparseArray<Long> buildServiceIndexToChannelId(String inputId) {
        SparseArray<Long> result = new SparseArray<>();

        int serviceCount = mServiceControl.getServiceListCount(SERVICE_LIST_INDEX);
        Log.d(TAG, "buildServiceIndexToChannelId: MW serviceCount=" + serviceCount);

        String[] projection = {
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
                TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
                TvContract.Channels.COLUMN_SERVICE_ID,
        };

        try (Cursor cursor = mContentResolver.query(
                TvContract.buildChannelsUriForInput(inputId), projection, null, null, null)) {
            if (cursor == null) {
                Log.w(TAG, "buildServiceIndexToChannelId: cursor null");
                return result;
            }

            for (int i = 0; i < serviceCount; i++) {
                ServiceDescriptor desc = mServiceControl.getServiceDescriptor(SERVICE_LIST_INDEX, i);
                if (desc == null) {
                    continue;
                }
                long channelId = findChannelId(cursor, desc.getONID(), desc.getTSID(), desc.getServiceId());
                if (channelId != -1) {
                    result.put(i, channelId);
                    Log.d(TAG, "mapped serviceIndex=" + i + " sid=" + desc.getServiceId() + " → channelId=" + channelId);
                } else {
                    Log.w(TAG, "no channel for serviceIndex=" + i + " sid=" + desc.getServiceId());
                }
            }
        }

        return result;
    }

    private static long findChannelId(Cursor cursor, int onid, int tsid, int sid) {
        cursor.moveToPosition(-1);
        int idxId   = cursor.getColumnIndexOrThrow(TvContract.Channels._ID);
        int idxOnid = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID);
        int idxTsid = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID);
        int idxSid  = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_SERVICE_ID);

        while (cursor.moveToNext()) {
            if (cursor.getInt(idxOnid) == onid
                    && cursor.getInt(idxTsid) == tsid
                    && cursor.getInt(idxSid) == sid) {
                return cursor.getLong(idxId);
            }
        }
        return -1;
    }
}
