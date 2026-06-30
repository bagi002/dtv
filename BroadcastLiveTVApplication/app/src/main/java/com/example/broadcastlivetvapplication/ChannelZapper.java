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

/**
 * Orkestrira zapping na jedan servis: dobija live rutu, postavlja Surface,
 * registruje ServiceListener i poziva startServiceByTriplet.
 * Takodje prati audio promene i izlaze ih kroz ResultListener.
 * Vidi: brodcast/Zapping/zapping_broadcast.puml
 */
final class ChannelZapper {

    private static final String TAG = "ChannelZapper";

    /** Callback za pracenje rezultata zappinga i promena audio traka. */
    interface ResultListener {
        /**
         * Pozvano kada MW potvrdi uspesnu promenu kanala (channelChangeStatus OK).
         *
         * @param liveRoute ID live rute na kojoj je kanal aktivan
         */
        void onChannelChanged(int liveRoute);

        /**
         * Pozvano kada je video spreman za prikaz (MW signal safeToUnblank).
         *
         * @param liveRoute ID live rute
         */
        void onSafeToUnblank(int liveRoute);

        /**
         * Pozvano ako bilo koji korak zappinga ne uspe.
         *
         * @param reason opis greske za logovanje/prikaz
         */
        void onZapError(String reason);

        /**
         * Audio se promenio na ruti (lista traka postala dostupna ili izmenjena).
         * Pozivac treba da osvezi listu audio traka.
         */
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

    /**
     * @param routeManagerControl MW kontroler za dobijanje live rute
     * @param displayControl      MW kontroler za postavljanje Surface-a i skaliranje prozora
     * @param serviceControl      MW kontroler za start/stop servisa i ServiceListener
     * @param audioControl        MW kontroler za audio trake i AudioListener
     * @param resultListener      prima obavesti o toku i rezultatu zappinga
     */
    ChannelZapper(ComediaRouteManagerControl routeManagerControl, DisplayControl displayControl,
            ServiceControl serviceControl, AudioControl audioControl, ResultListener resultListener) {
        mRouteManagerControl = routeManagerControl;
        mDisplayControl = displayControl;
        mServiceControl = serviceControl;
        mAudioControl = audioControl;
        mResultListener = resultListener;
    }

    /**
     * Sprovodi kompletnu sekvencu zappinga: getLiveRoute → setVideoSurface →
     * registerListener → startServiceByTriplet → scaleWindow.
     *
     * @param listIndex    indeks MW liste servisa (uvek 0)
     * @param serviceIndex indeks servisa u MW listi
     * @param onid         Original Network ID
     * @param tsid         Transport Stream ID
     * @param serviceId    Service ID
     * @param surface      Surface na koji se renderuje video
     */
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

    /**
     * Zaustavlja trenutni servis na ruti i odjavljuje ServiceListener i AudioListener.
     * Sigurno za pozivanje i ako startZap nije uspeo.
     */
    void stopZap() {
        mServiceControl.stopService(mLiveRoute);
        mServiceControl.unregisterListener(mServiceListener);
        if (mAudioListenerRegistered) {
            mAudioControl.unregisterListener(mAudioListener);
            mAudioListenerRegistered = false;
        }
    }

    /**
     * Vraca broj audio traka trenutnog kanala na aktivnoj ruti.
     *
     * @return broj audio traka; 0 ako ruta jos nije aktivna
     */
    int getAudioTrackCount() {
        return mAudioControl.getAudioTrackCount(mLiveRoute);
    }

    /**
     * Vraca opis audio trake po indeksu.
     *
     * @param trackIndex indeks trake (0-based)
     * @return {@link AudioTrack} objekat, ili {@code null} ako ruta jos nije aktivna ili indeks nije validan
     */
    AudioTrack getAudioTrack(int trackIndex) {
        return mAudioControl.getAudioTrack(mLiveRoute, trackIndex);
    }

    /**
     * Vraca indeks trenutno aktivne audio trake na ovoj ruti.
     *
     * @return indeks aktivne trake
     */
    int getCurrentAudioTrackIndex() {
        return mAudioControl.getCurrentAudioTrackIndex(mLiveRoute);
    }

    /**
     * Prebacuje audio traku na istoj ruti bez prekidanja videa.
     *
     * @param trackIndex indeks trake na koji treba prebaciti
     * @return {@code true} ako je MW prihvatio promenu, {@code false} ako nije uspelo
     */
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
