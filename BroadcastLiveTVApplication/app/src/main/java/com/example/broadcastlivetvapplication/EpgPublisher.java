package com.example.broadcastlivetvapplication;

import android.util.Log;
import android.util.SparseArray;

import com.iwedia.dtv.epg.EpgEvent;

import java.util.List;

import iwedia.dtv.epg.EpgControl;

/**
 * Koordinira EPG pipeline: za svaki servis u mapi čita PF evente sinhrono i
 * upisuje ih u TvContract.Programs. SC akvizicija se pokreće asinhrono.
 */
final class EpgPublisher {

    private static final String TAG = "EpgPublisher";

    private final EpgFetcher mFetcher;
    private final EpgProgramWriter mWriter;

    EpgPublisher(EpgControl epgControl, EpgProgramWriter writer) {
        mFetcher = new EpgFetcher(epgControl);
        mWriter  = writer;
    }

    /**
     * Za svaki serviceIndex u mapi čita PF evente i upisuje ih u TvProvider.
     *
     * @param channelMap mapa serviceIndex → TvContract channelId
     */
    void publishAll(SparseArray<Long> channelMap) {
        Log.d(TAG, "publishAll: " + channelMap.size() + " servisa");
        for (int i = 0; i < channelMap.size(); i++) {
            int serviceIndex = channelMap.keyAt(i);
            long channelId   = channelMap.valueAt(i);
            publishPresentFollowing(serviceIndex, channelId);
            publishSchedule(serviceIndex, channelId);
        }
    }

    private void publishPresentFollowing(int serviceIndex, long channelId) {
        List<EpgEvent> events = mFetcher.fetchPresentFollowing(serviceIndex);
        if (events.isEmpty()) {
            Log.d(TAG, "publishPresentFollowing: serviceIndex=" + serviceIndex + " 0 events");
            return;
        }
        long offset = EpgTimeShift.computeOffset(events);
        mWriter.writeEvents(channelId, events, offset);
        Log.d(TAG, "publishPresentFollowing: serviceIndex=" + serviceIndex
                + " channelId=" + channelId + " events=" + events.size());
    }

    private void publishSchedule(int serviceIndex, long channelId) {
        mFetcher.fetchScheduleAsync(serviceIndex, events -> {
            if (events.isEmpty()) {
                Log.d(TAG, "publishSchedule: serviceIndex=" + serviceIndex + " 0 events");
                return;
            }
            long offset = EpgTimeShift.computeOffset(events);
            mWriter.writeEvents(channelId, events, offset);
            Log.d(TAG, "publishSchedule: serviceIndex=" + serviceIndex
                    + " channelId=" + channelId + " events=" + events.size());
        });
    }
}
