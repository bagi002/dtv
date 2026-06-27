package com.example.broadcastlivetvapplication;

import android.util.Log;
import android.view.Surface;

import com.iwedia.dtv.A4TVStatus;
import com.iwedia.dtv.display.SurfaceBundle;
import com.iwedia.dtv.service.ServiceStateChangeError;
import com.iwedia.dtv.service.ServiceListUpdateData;

import iwedia.dtv.comediaroutemanager.ComediaRouteManagerControl;
import iwedia.dtv.display.DisplayControl;
import iwedia.dtv.service.ServiceControl;
import iwedia.dtv.service.ServiceListener;

/** Orkestrira zapping na jedan servis (vidi brodcast/Zapping/zapping_broadcast.puml). */
final class ChannelZapper {

    private static final String TAG = "ChannelZapper";

    interface ResultListener {
        void onChannelChanged(int liveRoute);
        void onSafeToUnblank(int liveRoute);
        void onZapError(String reason);
    }

    private final ComediaRouteManagerControl mRouteManagerControl;
    private final DisplayControl mDisplayControl;
    private final ServiceControl mServiceControl;
    private final ResultListener mResultListener;

    private int mLiveRoute = 0;
    private Surface mAppliedSurface;

    ChannelZapper(ComediaRouteManagerControl routeManagerControl, DisplayControl displayControl,
            ServiceControl serviceControl, ResultListener resultListener) {
        mRouteManagerControl = routeManagerControl;
        mDisplayControl = displayControl;
        mServiceControl = serviceControl;
        mResultListener = resultListener;
    }

    /** Sprovodi getLiveRoute -> setVideoSurface -> registerListener -> startServiceByTriplet redom iz dijagrama. */
    void startZap(int listIndex, int serviceIndex, int onid, int tsid, int serviceId, Surface surface) {
        int liveRoute = mRouteManagerControl.getLiveRoute(listIndex, serviceIndex, mLiveRoute);
        Log.d(TAG, "startZap getLiveRoute -> " + liveRoute);
        if (liveRoute == 0) {
            mResultListener.onZapError("getLiveRoute failed (no free route resource)");
            return;
        }
        mLiveRoute = liveRoute;

        // Postavi surface samo kad se stvarno promenio; ponovno postavljanje istog surface-a
        // na vec aktivan video plane zamrzava sliku pri promeni kanala.
        if (surface != mAppliedSurface) {
            A4TVStatus surfaceStatus = mDisplayControl.setVideoSurface(0, new SurfaceBundle(surface));
            Log.d(TAG, "startZap setVideoSurface -> " + surfaceStatus);
            if (surfaceStatus != A4TVStatus.SUCCESS) {
                mResultListener.onZapError("setVideoSurface failed: " + surfaceStatus);
                return;
            }
            mAppliedSurface = surface;
        }

        A4TVStatus registerStatus = mServiceControl.registerListener(mServiceListener);
        if (registerStatus != A4TVStatus.SUCCESS) {
            mResultListener.onZapError("registerListener failed: " + registerStatus);
            return;
        }

        A4TVStatus startStatus = mServiceControl.startServiceByTriplet(liveRoute, listIndex, serviceIndex, onid, tsid, serviceId);
        Log.d(TAG, "startZap startServiceByTriplet -> " + startStatus);
        if (startStatus != A4TVStatus.SUCCESS) {
            mServiceControl.unregisterListener(mServiceListener);
            mResultListener.onZapError("startServiceByTriplet failed: " + startStatus);
            return;
        }

        // Pozicioniraj/aktiviraj video prozor na ruti preko punog ekrana; bez ovoga video moze biti nepozicioniran.
        A4TVStatus scaleStatus = mDisplayControl.scaleWindow(liveRoute, 0, 0, 1920, 1080);
        Log.d(TAG, "startZap scaleWindow -> " + scaleStatus);
    }

    /** Zaustavlja trenutni servis na ruti i odjavljuje listener; sigurno za pozivanje i ako startZap nije uspeo. */
    void stopZap() {
        mServiceControl.stopService(mLiveRoute);
        mServiceControl.unregisterListener(mServiceListener);
    }

    private final ServiceListener mServiceListener = new ServiceListener() {
        @Override
        public void channelChangeStatus(int liveRoute, boolean channelChanged, ServiceStateChangeError reason) {
            Log.d(TAG, "channelChangeStatus liveRoute=" + liveRoute + " changed=" + channelChanged + " reason=" + reason);
            // Ignorisi callback-ove sa stare/druge rute (npr. zaostali nakon promene kanala).
            if (liveRoute != mLiveRoute) {
                return;
            }
            if (channelChanged && reason == ServiceStateChangeError.OK) {
                mResultListener.onChannelChanged(liveRoute);
            } else {
                mResultListener.onZapError("channelChangeStatus failed: " + reason);
            }
        }

        @Override
        public void safeToUnblank(int liveRoute) {
            Log.d(TAG, "safeToUnblank liveRoute=" + liveRoute);
            if (liveRoute != mLiveRoute) {
                return;
            }
            mResultListener.onSafeToUnblank(liveRoute);
        }

        @Override
        public void signalStatus(int liveRoute, boolean signalAvailable) {}
        @Override
        public void serviceScrambledStatus(int liveRoute, boolean channelScrambled) {}
        @Override
        public void serviceStopped(int liveRoute, boolean serviceStopped, ServiceStateChangeError reason) {}
        @Override
        public void updateServiceList(ServiceListUpdateData data) {}
        @Override
        public void onCaAlternativePresent(int caReplacementTsid, int caReplacementOnid, int caReplacementServiceId) {}
        @Override
        public void specialServiceEnded() {}
    };
}
