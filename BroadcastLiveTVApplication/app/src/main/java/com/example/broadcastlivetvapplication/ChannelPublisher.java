package com.example.broadcastlivetvapplication;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.iwedia.dtv.service.ServiceDescriptor;

import iwedia.dtv.service.ServiceControl;

/** Cita rezultate skeniranja iz Service & Mux DB preko ServiceControl i upisuje ih u TvContract.Channels. */
final class ChannelPublisher {

    private static final String TAG = "ChannelPublisher";
    private static final int SERVICE_LIST_INDEX = 0;

    private final ContentResolver mContentResolver;
    private final ServiceControl mServiceControl;

    ChannelPublisher(ContentResolver contentResolver, ServiceControl serviceControl) {
        mContentResolver = contentResolver;
        mServiceControl = serviceControl;
    }

    /** Upisuje sve trenutno instalirane servise za dati inputId; vraca broj upisanih kanala. */
    int publishInstalledServices(String inputId) {
        if (inputId == null) {
            return 0;
        }

        int numberOfLists = mServiceControl.getNumberOfServiceLists();
        int count = mServiceControl.getServiceListCount(SERVICE_LIST_INDEX);
        Log.d(TAG, "publishInstalledServices: numberOfServiceLists=" + numberOfLists + " getServiceListCount(0)=" + count);
        for (int i = 0; i < count; i++) {
            ServiceDescriptor descriptor = mServiceControl.getServiceDescriptor(SERVICE_LIST_INDEX, i);
            if (descriptor != null) {
                Log.d(TAG, "publishInstalledServices: i=" + i + " serviceId=" + descriptor.getServiceId()
                        + " onid=" + descriptor.getONID() + " tsid=" + descriptor.getTSID());
                upsertChannel(inputId, descriptor);
            } else {
                Log.w(TAG, "publishInstalledServices: i=" + i + " descriptor=null");
            }
        }
        return count;
    }

    private void upsertChannel(String inputId, ServiceDescriptor descriptor) {
        ContentValues values = new ContentValues();
        values.put(TvContract.Channels.COLUMN_INPUT_ID, inputId);
        values.put(TvContract.Channels.COLUMN_DISPLAY_NAME, descriptor.getName());
        values.put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, String.valueOf(descriptor.getServiceId()));
        values.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_DVB_T);
        values.put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO);
        values.put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, descriptor.getONID());
        values.put(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID, descriptor.getTSID());
        values.put(TvContract.Channels.COLUMN_SERVICE_ID, descriptor.getServiceId());

        Long existingChannelId = findChannelId(inputId, descriptor.getONID(), descriptor.getTSID(), descriptor.getServiceId());
        if (existingChannelId != null) {
            mContentResolver.update(TvContract.buildChannelUri(existingChannelId), values, null, null);
        } else {
            mContentResolver.insert(TvContract.Channels.CONTENT_URI, values);
        }
    }

    @Nullable
    private Long findChannelId(String inputId, int onid, int tsid, int serviceId) {
        Uri uri = TvContract.buildChannelsUriForInput(inputId);
        String[] projection = {
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
                TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
                TvContract.Channels.COLUMN_SERVICE_ID,
        };
        try (Cursor cursor = mContentResolver.query(uri, projection, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                if (cursor.getInt(1) == onid && cursor.getInt(2) == tsid && cursor.getInt(3) == serviceId) {
                    return cursor.getLong(0);
                }
            }
        }
        return null;
    }
}
