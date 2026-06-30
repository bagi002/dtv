package com.example.broadcastlivetvapplication;

import android.util.Log;

import androidx.annotation.NonNull;

import com.iwedia.dtv.A4TVStatus;
import com.iwedia.dtv.comediaroutemanager.RouteManagerMediumType;
import com.iwedia.dtv.scan.ScanInstallStatus;

import iwedia.dtv.comediaroutemanager.ComediaRouteManagerControl;
import iwedia.dtv.scan.ScanControl;
import iwedia.dtv.scan.ScanListener;

/**
 * Orkestrira autoScan kroz niz .ts izvora sekvencijalno.
 * Nakon svakog zavrsenog izvora obavestavlja ResultListener pre nego sto pocne sledeci
 * (MW drzi samo jedan multipleks — stari se brise kada sledeci autoScan pocne).
 * Vidi: brodcast/Service_installation/service_installation_broadcast.puml
 */
final class ChannelScanner {

    private static final String TAG = "ChannelScanner";

    /** Callback za pracenje napretka i rezultata sekvencijalnog skena svih izvora. */
    interface ResultListener {
        /**
         * Pozvano pre pocetka skena jednog izvora.
         *
         * @param sourceIndex  redni broj trenutnog izvora (od 1)
         * @param sourceCount  ukupan broj izvora
         * @param sourceUrl    URL izvora koji se skenira
         */
        void onScanProgress(int sourceIndex, int sourceCount, String sourceUrl);

        /**
         * Pozvano cim je jedan izvor skeniran — pre nego sledeci autoScan obrise njegov multipleks.
         * Pozivac treba da odmah procita MW listu servisa.
         *
         * @param sourceUrl URL izvora koji je upravo skeniran
         */
        void onSourceScanned(String sourceUrl);

        /** Pozvano kada su svi izvori uspesno skenirani. */
        void onScanFinished();

        /**
         * Pozvano ako scan nije mogao da se pokrene ili je prekinut.
         *
         * @param reason opis greske za logovanje/prikaz
         */
        void onScanError(String reason);
    }

    /** Callback za jednokratan re-scan jednog izvora (vidi {@link #rescanSingleSource}). */
    interface RescanCallback {
        /**
         * Pozvano kada je re-scan uspesno zavrsen.
         *
         * @param sourceUrl URL izvora koji je re-skeniran
         */
        void onRescanFinished(String sourceUrl);

        /**
         * Pozvano ako re-scan nije uspeo.
         *
         * @param sourceUrl URL izvora koji je trebalo re-skenirati
         * @param reason    opis greske
         */
        void onRescanError(String sourceUrl, String reason);
    }

    private final ComediaRouteManagerControl mRouteManagerControl;
    private final ScanControl mScanControl;
    private final ResultListener mResultListener;

    private String[] mSourceUrls = new String[0];
    private int mInstallRouteId = -1;
    private int mNextSourceIndex = 0;

    /**
     * @param routeManagerControl MW kontroler za dobijanje install rute
     * @param scanControl         MW kontroler za pokretanje autoScan-a
     * @param resultListener      prima obavesti o toku i rezultatu skena
     */
    ChannelScanner(ComediaRouteManagerControl routeManagerControl, ScanControl scanControl, ResultListener resultListener) {
        mRouteManagerControl = routeManagerControl;
        mScanControl = scanControl;
        mResultListener = resultListener;
    }

    /**
     * Pokrece sekvencijalni autoScan za svaki URL iz niza; rezultati stizu kroz {@link ResultListener}.
     *
     * @param sourceUrls niz URL-ova .ts izvora koje treba skenirati redom
     */
    void startScan(@NonNull String[] sourceUrls) {
        mSourceUrls = sourceUrls;
        if (!ensureInstallRouteId()) {
            mResultListener.onScanError("getInstallRoute failed (no free route resource)");
            return;
        }

        A4TVStatus registerStatus = mScanControl.registerListener(mScanListener);
        if (registerStatus != A4TVStatus.SUCCESS) {
            mResultListener.onScanError("registerListener failed: " + registerStatus);
            return;
        }

        mNextSourceIndex = 0;
        scanNextSource();
    }

    /**
     * Obezbedjuje da mInstallRouteId postoji; isti route se ponovo koristi i za startScan i za rescanSingleSource.
     *
     * @return {@code true} ako je install route uspesno dobijen ili vec postoji
     */
    private boolean ensureInstallRouteId() {
        if (mInstallRouteId != 0 && mInstallRouteId != -1) {
            return true;
        }
        mInstallRouteId = mRouteManagerControl.getInstallRoute(RouteManagerMediumType.MEDIUM_IP);
        Log.d(TAG, "ensureInstallRouteId -> " + mInstallRouteId);
        return mInstallRouteId != 0;
    }

    /**
     * Pokrece autoScan za sledeci izvor iz mSourceUrls.
     * appendList=false samo za prvi izvor (brise staru MW listu); za ostale append=true.
     */
    private void scanNextSource() {
        boolean isFirstSource = mNextSourceIndex == 0;
        String sourceUrl = mSourceUrls[mNextSourceIndex];

        mScanControl.appendList(!isFirstSource);

        Log.d(TAG, "scanNextSource: index=" + mNextSourceIndex + " url=" + sourceUrl);
        mResultListener.onScanProgress(mNextSourceIndex + 1, mSourceUrls.length, sourceUrl);

        A4TVStatus status = mScanControl.autoScan(mInstallRouteId, sourceUrl);
        if (status != A4TVStatus.SUCCESS) {
            mScanControl.unregisterListener(mScanListener);
            mResultListener.onScanError("autoScan failed for " + sourceUrl + ": " + status);
        }
    }

    /**
     * Odjavljuje glavni ScanListener.
     * Poziva se pri gasenju servisa da ne ostane viseci callback.
     */
    void unregisterListener() {
        mScanControl.unregisterListener(mScanListener);
    }

    /**
     * Jednokratan re-scan tacno jednog izvora, van glavne instalacione petlje.
     * Koristi se pri tune-u kad ChannelLookup ne nadje servis u trenutnoj MW listi —
     * znak da je njen multipleks u medjuvremenu obrisan jer je neki drugi izvor skeniran posle njega.
     * Radi i ako startScan nikad nije pozvan u ovom procesu (npr. nakon restarta TIS procesa).
     *
     * @param sourceUrl URL .ts izvora koji treba ponovo skenirati
     * @param callback  prima obavest kada re-scan zavrsi ili ne uspe
     */
    void rescanSingleSource(@NonNull String sourceUrl, @NonNull RescanCallback callback) {
        if (!ensureInstallRouteId()) {
            callback.onRescanError(sourceUrl, "rescanSingleSource: getInstallRoute failed (no free route resource)");
            return;
        }

        ScanListener rescanListener = new ScanListener() {
            @Override
            public void scanFinished(int routeId) {
                Log.d(TAG, "rescanSingleSource: scanFinished url=" + sourceUrl);
                mScanControl.unregisterListener(this);
                callback.onRescanFinished(sourceUrl);
            }

            @Override
            public void scanAborted(int routeId, int broadcastType) {
                Log.e(TAG, "rescanSingleSource: scanAborted url=" + sourceUrl + " broadcastType=" + broadcastType);
                mScanControl.unregisterListener(this);
                callback.onRescanError(sourceUrl, "scan aborted: " + broadcastType);
            }

            @Override
            public void installStatus(ScanInstallStatus status) {}
            @Override
            public void scanTunFrequency(int routeId, int frequency) {}
            @Override
            public void installServiceTVName(int routeId, String name) {}
            @Override
            public void installServiceTVNumber(int routeId, int number) {}
            @Override
            public void tunerLocked(int id, boolean locked) {}
            @Override
            public void installServiceRADIOName(int routeId, String name) {}
            @Override
            public void installServiceDATAName(int routeId, String name) {}
            @Override
            public void installServiceRADIONumber(int routeId, int number) {}
            @Override
            public void installServiceDATANumber(int routeId, int number) {}
            @Override
            public void scanProgressChanged(int routeId, int value) {}
            @Override
            public void antennaConnected(int routeId, boolean state) {}
            @Override
            public void signalQuality(int routeId, int quality) {}
            @Override
            public void signalStrength(int routeId, int strength) {}
            @Override
            public void signalBer(int routeId, int ber) {}
            @Override
            public void scanNoServiceSpace(int routeId) {}
            @Override
            public void networkChanged(int networkId) {}
            @Override
            public void sat2ipServerDropped(int routeId) {}
            @Override
            public void triggerStatus(int routeId) {}
            @Override
            public void signalLost() {}
            @Override
            public void signalReturned() {}
        };

        A4TVStatus registerStatus = mScanControl.registerListener(rescanListener);
        if (registerStatus != A4TVStatus.SUCCESS) {
            callback.onRescanError(sourceUrl, "registerListener failed: " + registerStatus);
            return;
        }

        Log.d(TAG, "rescanSingleSource: url=" + sourceUrl);
        mScanControl.appendList(false);
        A4TVStatus status = mScanControl.autoScan(mInstallRouteId, sourceUrl);
        if (status != A4TVStatus.SUCCESS) {
            mScanControl.unregisterListener(rescanListener);
            callback.onRescanError(sourceUrl, "autoScan failed: " + status);
        }
    }

    private final ScanListener mScanListener = new ScanListener() {
        @Override
        public void installStatus(ScanInstallStatus status) {
            Log.d(TAG, "installStatus routeId=" + status.getInstallRouteId());
        }

        @Override
        public void scanTunFrequency(int routeId, int frequency) {
        }

        @Override
        public void scanFinished(int routeId) {
            String scannedUrl = mSourceUrls[mNextSourceIndex];
            Log.d(TAG, "scanFinished routeId=" + routeId + " source=" + scannedUrl);
            // Procitaj servise ovog izvora ODMAH, pre nego sledeci autoScan obrise njegov multipleks.
            mResultListener.onSourceScanned(scannedUrl);

            mNextSourceIndex++;
            if (mNextSourceIndex < mSourceUrls.length) {
                scanNextSource();
                return;
            }

            mScanControl.unregisterListener(mScanListener);
            mResultListener.onScanFinished();
        }
        @Override
        public void scanAborted(int routeId, int broadcastType) {
            Log.e(TAG, "scanAborted routeId=" + routeId + " broadcastType=" + broadcastType);
            mScanControl.unregisterListener(mScanListener);
            mResultListener.onScanError("scan aborted: " + broadcastType);
        }

        @Override
        public void installServiceTVName(int routeId, String name) {
            Log.d(TAG, "installServiceTVName: " + name);
        }

        @Override
        public void installServiceTVNumber(int routeId, int number) {
            Log.d(TAG, "installServiceTVNumber: " + number);
        }

        @Override
        public void tunerLocked(int id, boolean locked) {
            Log.d(TAG, "tunerLocked id=" + id + " locked=" + locked);
        }

        @Override
        public void installServiceRADIOName(int routeId, String name) {}
        @Override
        public void installServiceDATAName(int routeId, String name) {}
        @Override
        public void installServiceRADIONumber(int routeId, int number) {}
        @Override
        public void installServiceDATANumber(int routeId, int number) {}
        @Override
        public void scanProgressChanged(int routeId, int value) {}
        @Override
        public void antennaConnected(int routeId, boolean state) {}
        @Override
        public void signalQuality(int routeId, int quality) {}
        @Override
        public void signalStrength(int routeId, int strength) {}
        @Override
        public void signalBer(int routeId, int ber) {}
        @Override
        public void scanNoServiceSpace(int routeId) {}
        @Override
        public void networkChanged(int networkId) {}
        @Override
        public void sat2ipServerDropped(int routeId) {}
        @Override
        public void triggerStatus(int routeId) {}
        @Override
        public void signalLost() {}
        @Override
        public void signalReturned() {}
    };
}
