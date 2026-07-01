package com.example.broadcastlivetvapplication;

import android.util.Log;

import com.iwedia.dtv.epg.EpgEvent;
import com.iwedia.dtv.epg.EpgEventType;
import com.iwedia.dtv.epg.EpgServiceFilter;

import java.util.ArrayList;
import java.util.List;

import iwedia.dtv.epg.EpgControl;
import iwedia.dtv.epg.EpgListener;

/**
 * Čita EPG evente iz Comedia middleware-a za jedan serviceIndex.
 *
 * PF (Present/Following) se čita sinhrono — MW vraća podatke odmah jer se
 * demux dekodir PF tabelu čim je servis aktivan pri skenu.
 * SC (Schedule) je asinhrono — akvizicija traje i rezultat stiže kroz callback.
 */
final class EpgFetcher {

    private static final String TAG = "EpgFetcher";

    interface ScCallback {
        void onScFetched(List<EpgEvent> events);
    }

    private final EpgControl mEpgControl;

    EpgFetcher(EpgControl epgControl) {
        mEpgControl = epgControl;
    }

    /**
     * Sinhrono čita Present i Following evente za dati servis.
     *
     * @param serviceIndex MW serviceIndex
     * @return lista od 0–2 evenata (PRESENT, FOLLOWING)
     */
    List<EpgEvent> fetchPresentFollowing(int serviceIndex) {
        int filterID = mEpgControl.createEventList();
        applyServiceFilter(filterID, serviceIndex);

        List<EpgEvent> events = readPfEvents(filterID, serviceIndex);
        mEpgControl.releaseEventList(filterID);

        Log.d(TAG, "fetchPresentFollowing: serviceIndex=" + serviceIndex + " events=" + events.size());
        return events;
    }

    /**
     * Pokreće asinhronu SC akviziciju za dati servis.
     * Callback se poziva kada MW završi akviziciju i eventi su dostupni.
     *
     * @param serviceIndex MW serviceIndex
     * @param callback     prima listu SC evenata (može biti prazna)
     */
    void fetchScheduleAsync(int serviceIndex, ScCallback callback) {
        int filterID = mEpgControl.createEventList();
        applyServiceFilter(filterID, serviceIndex);

        mEpgControl.registerListener(new EpgListener() {
            @Override
            public void scAcquisitionFinished(int id, int idx) {
                if (id != filterID || idx != serviceIndex) {
                    return;
                }
                mEpgControl.unregisterListener(this);
                List<EpgEvent> events = readScEvents(filterID, serviceIndex);
                mEpgControl.releaseEventList(filterID);
                Log.d(TAG, "scAcquisitionFinished: serviceIndex=" + serviceIndex + " events=" + events.size());
                callback.onScFetched(events);
            }

            @Override public void pfAcquisitionFinished(int id, int idx) {}
            @Override public void pfEventChanged(int id, int idx) {}
            @Override public void scEventChanged(int id, int idx) {}
            @Override public void tdtChanged(int id, int idx) {}
            @Override public void pvrPfEventChanged(int id, int idx) {}
            @Override public void pvrPfAcquisitionFinished(int id, int idx) {}
        }, filterID);

        mEpgControl.startAcquisition(filterID);
    }

    private void applyServiceFilter(int filterID, int serviceIndex) {
        EpgServiceFilter filter = new EpgServiceFilter();
        filter.setServiceIndex(serviceIndex);
        mEpgControl.setFilter(filterID, filter);
    }

    private List<EpgEvent> readPfEvents(int filterID, int serviceIndex) {
        List<EpgEvent> events = new ArrayList<>();
        addIfNotNull(events, mEpgControl.getPresentFollowingEvent(filterID, serviceIndex, EpgEventType.PRESENT_EVENT));
        addIfNotNull(events, mEpgControl.getPresentFollowingEvent(filterID, serviceIndex, EpgEventType.FOLLOWING_EVENT));
        return events;
    }

    private List<EpgEvent> readScEvents(int filterID, int serviceIndex) {
        List<EpgEvent> events = new ArrayList<>();
        int count = mEpgControl.getAvailableEventsNumber(filterID, serviceIndex);
        for (int i = 0; i < count; i++) {
            addIfNotNull(events, mEpgControl.getRequestedEvent(filterID, serviceIndex, i));
        }
        return events;
    }

    private static void addIfNotNull(List<EpgEvent> list, EpgEvent event) {
        if (event != null) {
            list.add(event);
        }
    }
}
