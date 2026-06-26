package com.example.broadcastlivetvapplication;

import android.util.Log;

import androidx.annotation.NonNull;

import com.iwedia.dtv.A4TVStatus;
import com.iwedia.dtv.comediaroutemanager.RouteManagerMediumType;
import com.iwedia.dtv.scan.ScanInstallStatus;

import iwedia.dtv.comediaroutemanager.ComediaRouteManagerControl;
import iwedia.dtv.scan.ScanControl;
import iwedia.dtv.scan.ScanListener;

/** Orkestrira autoScan kroz niz izvora (vidi brodcast/Service_installation/service_installation_broadcast.puml). */
final class ChannelScanner {

    private static final String TAG = "ChannelScanner";

    interface ResultListener {
        void onScanProgress(int sourceIndex, int sourceCount, String sourceUrl);
        void onScanFinished();
        void onScanError(String reason);
    }

    private final ComediaRouteManagerControl mRouteManagerControl;
    private final ScanControl mScanControl;
    private final ResultListener mResultListener;

    private String[] mSourceUrls = new String[0];
    private int mInstallRouteId = -1;
    private int mNextSourceIndex = 0;

    ChannelScanner(ComediaRouteManagerControl routeManagerControl, ScanControl scanControl, ResultListener resultListener) {
        mRouteManagerControl = routeManagerControl;
        mScanControl = scanControl;
        mResultListener = resultListener;
    }

    void startScan(@NonNull String[] sourceUrls) {
        mSourceUrls = sourceUrls;
        mInstallRouteId = mRouteManagerControl.getInstallRoute(RouteManagerMediumType.MEDIUM_IP);
        Log.d(TAG, "startScan installRouteId=" + mInstallRouteId);

        if (mInstallRouteId == 0) {
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

    /** Pokrece autoScan za sledeci izvor iz mSourceUrls; append=false samo za prvi (brise staru listu). */
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

    void unregisterListener() {
        mScanControl.unregisterListener(mScanListener);
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
            Log.d(TAG, "scanFinished routeId=" + routeId + " source=" + mSourceUrls[mNextSourceIndex]);
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
