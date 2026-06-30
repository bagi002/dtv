package com.example.broadcastlivetvapplication;

import android.util.Log;
import android.view.Surface;

import com.iwedia.dtv.A4TVStatus;
import com.iwedia.dtv.audio.AudioTrack;
import com.iwedia.dtv.display.SurfaceBundle;
import com.iwedia.dtv.service.ServiceStateChangeError;
import com.iwedia.dtv.service.ServiceListUpdateData;
import com.iwedia.dtv.types.AudioDigitalType;

import iwedia.dtv.audio.AudioControl;
import iwedia.dtv.audio.AudioListener;
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
        /** Audio se promenio na ruti (npr. lista traka spremna/izmenjena) — pozivac treba da osvezi listu traka. */
        void onAudioTracksChanged();
    }

    private final ComediaRouteManagerControl mRouteManagerControl;
    private final DisplayControl mDisplayControl;
    private final ServiceControl mServiceControl;
    private final AudioControl mAudioControl;
    private final ResultListener mResultListener;

    private int mLiveRoute = 0;
    private Surface mAppliedSurface;
    private boolean mAudioListenerRegistered;

    ChannelZapper(ComediaRouteManagerControl routeManagerControl, DisplayControl displayControl,
            ServiceControl serviceControl, AudioControl audioControl, ResultListener resultListener) {
        mRouteManagerControl = routeManagerControl;
        mDisplayControl = displayControl;
        mServiceControl = serviceControl;
        mAudioControl = audioControl;
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

        // Prati promene audija na ruti da bi sesija mogla da osvezi listu traka kad postanu spremne.
        if (!mAudioListenerRegistered) {
            A4TVStatus audioStatus = mAudioControl.registerListener(mAudioListener);
            Log.d(TAG, "startZap registerListener(audio) -> " + audioStatus);
            mAudioListenerRegistered = audioStatus == A4TVStatus.SUCCESS;
        }
    }

    /** Zaustavlja trenutni servis na ruti i odjavljuje listenere; sigurno za pozivanje i ako startZap nije uspeo. */
    void stopZap() {
        mServiceControl.stopService(mLiveRoute);
        mServiceControl.unregisterListener(mServiceListener);
        if (mAudioListenerRegistered) {
            mAudioControl.unregisterListener(mAudioListener);
            mAudioListenerRegistered = false;
        }
    }

    /** Broj audio traka trenutnog kanala na ovoj ruti (vidi audioTapes.md). */
    int getAudioTrackCount() {
        return mAudioControl.getAudioTrackCount(mLiveRoute);
    }

    /** Opis audio trake po indeksu; null ako ruta jos nije aktivna ili index nije validan. */
    AudioTrack getAudioTrack(int trackIndex) {
        return mAudioControl.getAudioTrack(mLiveRoute, trackIndex);
    }

    /** Indeks trenutno aktivne audio trake na ovoj ruti. */
    int getCurrentAudioTrackIndex() {
        return mAudioControl.getCurrentAudioTrackIndex(mLiveRoute);
    }

    /** Prebacuje audio traku na istoj ruti — video se ne prekida. Vraca true ako je MW prihvatio. */
    boolean selectAudioTrack(int trackIndex) {
        A4TVStatus status = mAudioControl.setCurrentAudioTrack(mLiveRoute, trackIndex);
        Log.d(TAG, "selectAudioTrack(" + trackIndex + ") -> " + status);
        return status == A4TVStatus.SUCCESS;
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

    private final AudioListener mAudioListener = new AudioListener() {
        @Override
        public void typeChanged(int liveRoute, AudioDigitalType audioType) {
            Log.d(TAG, "audio typeChanged liveRoute=" + liveRoute + " type=" + audioType);
            if (liveRoute == mLiveRoute) {
                mResultListener.onAudioTracksChanged();
            }
        }

        @Override
        public void sampleRateChanged(int liveRoute, int sampleRate) {}

        @Override
        public void audioChannelChanged(int liveRoute, int audioChannel) {}
    };
}
