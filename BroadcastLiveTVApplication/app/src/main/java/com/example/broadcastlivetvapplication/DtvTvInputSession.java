package com.example.broadcastlivetvapplication;

import android.content.Context;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.iwedia.dtv.audio.AudioTrack;
import com.iwedia.dtv.types.AudioChannelConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * TIF sesija — session-strana zappinga.
 * Drzi Surface i volumen, a sve tezke operacije (lookup, rescan, zap, audio)
 * delegira na {@link ChannelLookup} i {@link ChannelZapper} asinhrono na tune threadu
 * kako se ne bi probio TIF limit od 2s za onTune.
 * Vidi: brodcast/Zapping/zapping_broadcast.puml
 */
final class DtvTvInputSession extends TvInputService.Session {

    private static final String TAG = "DtvTvInputSession";

    private final Context mContext;
    private final DtvTvInputService mService;
    private final ComediaMiddlewareConnection mMiddlewareConnection;
    // Sav tune/zap/audio rad (sinhroni middleware IPC + autoScan) ide na ovaj thread; TIF ubija proces
    // ako onTune blokira glavni thread > 2s ("Too much time to handle tune request").
    private final HandlerThread mTuneThread;
    private final Handler mTuneHandler;

    // Pise se na glavnom threadu (onSetSurface), cita na tune threadu (startZapWithResult).
    private volatile Surface mSurface;
    private float mStreamVolume = 1.0f;
    // mZapper, mVideoRevealed: dodirivani ISKLJUCIVO sa mTuneHandler threada (bez sinhronizacije).
    private ChannelZapper mZapper;
    private boolean mVideoRevealed;
    private Uri mPendingChannelUri;

    /**
     * @param context               Android context
     * @param service               roditeljski servis; koristi se za getChannelScanner i onSessionReleased
     * @param middlewareConnection  konekcija ka MW; mora biti dostupna pre prvog tune-a
     */
    DtvTvInputSession(Context context, DtvTvInputService service, ComediaMiddlewareConnection middlewareConnection) {
        super(context);
        mContext = context;
        mService = service;
        mMiddlewareConnection = middlewareConnection;
        mTuneThread = new HandlerThread("DtvTune");
        mTuneThread.start();
        mTuneHandler = new Handler(mTuneThread.getLooper());
    }

    /**
     * Poziva {@link DtvTvInputService} kada Comedia postane dostupna.
     * Ako je tune bio na cekanju (MW nije bio spreman), ponavlja ga.
     */
    void onMiddlewareReady() {
        if (mPendingChannelUri != null) {
            Log.d(TAG, "onMiddlewareReady: ponavljam pending tune " + mPendingChannelUri);
            Uri uri = mPendingChannelUri;
            mPendingChannelUri = null;
            mTuneHandler.post(() -> doTune(uri));
        }
    }

    /**
     * Prima Surface od TIF-a i cuva ga za sledeci zap.
     *
     * @param surface novi Surface za renderovanje videa, ili {@code null} za uklanjanje
     * @return uvek {@code true}
     */
    @Override
    public boolean onSetSurface(Surface surface) {
        Log.d(TAG, "onSetSurface: " + surface);
        mSurface = surface;
        return true;
    }

    /**
     * Prima zahtev za jacinu zvuka od TIF-a.
     *
     * @param volume vrednost izmedju 0.0 i 1.0
     */
    @Override
    public void onSetStreamVolume(float volume) {
        Log.d(TAG, "onSetStreamVolume: " + volume);
        mStreamVolume = volume;
    }

    @Override
    public void onSetCaptionEnabled(boolean enabled) {
    }

    /**
     * Prima zahtev za promenu kanala od TIF-a.
     * Odmah se vraca; tezak posao (lookup + eventualni rescan + zap) ide na tune thread
     * da se ne probije TIF limit od 2s.
     *
     * @param channelUri URI kanala iz TvContract.Channels
     * @return uvek {@code true}
     */
    @Override
    public boolean onTune(Uri channelUri) {
        Log.d(TAG, "onTune: " + channelUri);
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
        // Middleware mozda jos nije spreman ako TIF okine onTune odmah po pokretanju procesa;
        // zapamti zahtev i ponovi ga iz onMiddlewareReady().
        if (!mMiddlewareConnection.isAvailable()) {
            Log.w(TAG, "onTune: middleware not available yet, cekam");
            mPendingChannelUri = channelUri;
            return true;
        }
        // Vrati se odmah; tezak posao (lookup + rescan + zap) ide na mTuneHandler da se ne probije 2s limit.
        mTuneHandler.post(() -> doTune(channelUri));
        return true;
    }

    /**
     * Trazi servis u MW listi; ako nije nadjen, pokree rescan pa ponovo proba.
     * Izvrsava se na tune threadu.
     *
     * @param channelUri URI kanala koji treba otvoriti
     */
    private void doTune(Uri channelUri) {
        ChannelLookup.Result result = ChannelLookup.findServiceIndex(
                mContext.getContentResolver(), mMiddlewareConnection.getServiceControl(), channelUri);
        if (result == null) {
            // Servis nije u trenutnoj middleware listi — njegov multipleks je verovatno obrisan
            // jer je neki drugi izvor skeniran posle njega. Re-skeniraj bas njegov .ts i probaj opet.
            rescanAndRetryTune(channelUri);
            return;
        }

        startZapWithResult(result);
    }

    /**
     * Re-skenira .ts izvor kanala, pa ponovo pokusava da nadje servis i zapuje.
     * Koristi se kada {@link ChannelLookup#findServiceIndex} vrati {@code null}.
     *
     * @param channelUri URI kanala koji ceka na tune
     */
    private void rescanAndRetryTune(Uri channelUri) {
        String sourceUrl = ChannelLookup.readSourceUrl(mContext.getContentResolver(), channelUri);
        ChannelScanner scanner = mService.getChannelScanner();
        Log.d(TAG, "rescanAndRetryTune: sourceUrl=" + sourceUrl + " scanner=" + scanner);
        if (sourceUrl == null || scanner == null) {
            Log.e(TAG, "rescanAndRetryTune: nema sourceUrl ili scanner za " + channelUri);
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
            return;
        }

        Log.d(TAG, "rescanAndRetryTune: re-skeniram " + sourceUrl + " za " + channelUri);
        scanner.rescanSingleSource(sourceUrl, new ChannelScanner.RescanCallback() {
            @Override
            public void onRescanFinished(String rescannedUrl) {
                Log.d(TAG, "rescanAndRetryTune: rescan zavrsen za " + rescannedUrl);
                mTuneHandler.post(() -> {
                    ChannelLookup.Result result = ChannelLookup.findServiceIndex(
                            mContext.getContentResolver(), mMiddlewareConnection.getServiceControl(), channelUri);
                    if (result == null) {
                        Log.e(TAG, "rescanAndRetryTune: servis i dalje nije nadjen nakon rescan-a");
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                        return;
                    }
                    startZapWithResult(result);
                });
            }

            @Override
            public void onRescanError(String rescannedUrl, String reason) {
                Log.e(TAG, "rescanAndRetryTune: rescan failed za " + rescannedUrl + ": " + reason);
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
            }
        });
    }

    /**
     * Kreira {@link ChannelZapper} ako jos ne postoji (ili zaustavlja prethodni zap),
     * pa pokrece zap za pronadjeni servis.
     *
     * @param result rezultat pretrage iz {@link ChannelLookup#findServiceIndex}
     */
    private void startZapWithResult(ChannelLookup.Result result) {
        if (mZapper == null) {
            mZapper = new ChannelZapper(
                    mMiddlewareConnection.getRouteManagerControl(),
                    mMiddlewareConnection.getDisplayControl(),
                    mMiddlewareConnection.getServiceControl(),
                    mMiddlewareConnection.getAudioControl(),
                    new ChannelZapper.ResultListener() {
                        @Override
                        public void onChannelChanged(int liveRoute) {
                            Log.d(TAG, "onChannelChanged liveRoute=" + liveRoute);
                            // Neke verzije MW-a ne emituju safeToUnblank; otkrivamo video i ovde (idempotentno).
                            mTuneHandler.post(DtvTvInputSession.this::revealVideo);
                        }

                        @Override
                        public void onSafeToUnblank(int liveRoute) {
                            Log.d(TAG, "onSafeToUnblank liveRoute=" + liveRoute);
                            mTuneHandler.post(DtvTvInputSession.this::revealVideo);
                        }

                        @Override
                        public void onZapError(String reason) {
                            Log.e(TAG, "onZapError: " + reason);
                            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                        }

                        @Override
                        public void onAudioTracksChanged() {
                            mTuneHandler.post(DtvTvInputSession.this::publishAudioTracks);
                        }
                    });
        } else {
            // Promena kanala na vec aktivnoj sesiji: oslobodi prethodni servis/listener pre novog zapa.
            mZapper.stopZap();
        }

        // Novi kanal: video je ponovo skriven dok MW ne potvrdi promenu.
        mVideoRevealed = false;

        mZapper.startZap(result.listIndex, result.serviceIndex, result.onid, result.tsid, result.serviceId, mSurface);
    }

    /**
     * Otkriva video kad MW potvrdi uspesnu promenu kanala i gradi listu audio traka.
     * Idempotentno — poziva se i iz channelChangeStatus i iz safeToUnblank.
     */
    private void revealVideo() {
        if (mVideoRevealed) {
            return;
        }
        mVideoRevealed = true;
        notifyVideoAvailable();
        // Audio komponente su spremne tek nakon potvrde promene kanala — tek sad gradi listu traka.
        publishAudioTracks();
    }

    /**
     * Cita audio trake aktivne rute iz MW-a i objavljuje ih TIF-u (sistemski meni za audio).
     * Poziva se tek nakon sto je video otkriven (trake nisu dostupne pre toga).
     */
    private void publishAudioTracks() {
        if (mZapper == null || !mVideoRevealed) {
            return;
        }
        int count = mZapper.getAudioTrackCount();
        List<TvTrackInfo> tracks = new ArrayList<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            AudioTrack track = mZapper.getAudioTrack(i);
            if (track == null) {
                continue;
            }
            TvTrackInfo.Builder builder =
                    new TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, String.valueOf(track.getIndex()));
            String language = track.getLanguage();
            if (!TextUtils.isEmpty(language)) {
                builder.setLanguage(language);
            }
            int channelCount = channelCount(track.getAudioChannleCfg());
            if (channelCount > 0) {
                builder.setAudioChannelCount(channelCount);
            }
            tracks.add(builder.build());
        }
        Log.d(TAG, "publishAudioTracks: " + tracks.size() + " audio traka");
        notifyTracksChanged(tracks);
        if (!tracks.isEmpty()) {
            notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, String.valueOf(mZapper.getCurrentAudioTrackIndex()));
        }
    }

    /**
     * Prima zahtev korisnika za promenu audio trake.
     * Prebacivanje se izvrsava asinhrono na tune threadu; TIF se obavestava tek kad MW prihvati.
     *
     * @param type    tip trake; samo {@link TvTrackInfo#TYPE_AUDIO} je podrzan
     * @param trackId string indeks trake (konvertuje se u int)
     * @return {@code true} ako je zahtev prihvacen i asinhrono prosledjen
     */
    @Override
    public boolean onSelectTrack(int type, String trackId) {
        if (type != TvTrackInfo.TYPE_AUDIO || trackId == null) {
            return false;
        }
        final int trackIndex;
        try {
            trackIndex = Integer.parseInt(trackId);
        } catch (NumberFormatException e) {
            Log.e(TAG, "onSelectTrack: nevalidan trackId " + trackId);
            return false;
        }
        // Prebacivanje trake (middleware IPC) na tune thread; potvrdi tek kad MW prihvati. Video/ruta se ne diraju.
        mTuneHandler.post(() -> {
            if (mZapper != null && mZapper.selectAudioTrack(trackIndex)) {
                notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, trackId);
            }
        });
        return true;
    }

    /**
     * Mapira A4TV konfiguraciju audio kanala u broj kanala za {@link TvTrackInfo}.
     *
     * @param cfg konfiguracija audio kanala iz MW-a
     * @return broj kanala (1, 2 ili 6), ili 0 ako je nepoznato (polje se tada ne postavlja)
     */
    private static int channelCount(AudioChannelConfiguration cfg) {
        if (cfg == null) {
            return 0;
        }
        switch (cfg) {
            case MONO:
            case DUAL_MONO:
                return 1;
            case STEREO:
            case SURROUND_STEREO:
                return 2;
            case MULTICHANNEL_2_PLUS:
            case MULTICHANNEL_5_PLUS:
                return 6;
            default:
                return 0;
        }
    }

    /**
     * Oslobadja MW rutu i gasi tune thread.
     * Ruta se oslobadja na tune threadu da ne ostane zaglavljena u MW-u.
     */
    @Override
    public void onRelease() {
        // Oslobodi rutu na tune threadu (da ne ostane zaglavljena u middleware-u), pa ugasi thread.
        mTuneHandler.post(() -> {
            if (mZapper != null) {
                mZapper.stopZap();
            }
        });
        mTuneThread.quitSafely();
        mService.onSessionReleased(this);
    }
}
