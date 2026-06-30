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

/** Session-strana zappinga: drzi Surface/volume i delegira na ChannelLookup + ChannelZapper (vidi brodcast/Zapping/zapping_broadcast.puml). */
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

    DtvTvInputSession(Context context, DtvTvInputService service, ComediaMiddlewareConnection middlewareConnection) {
        super(context);
        mContext = context;
        mService = service;
        mMiddlewareConnection = middlewareConnection;
        mTuneThread = new HandlerThread("DtvTune");
        mTuneThread.start();
        mTuneHandler = new Handler(mTuneThread.getLooper());
    }

    /** Poziva DtvTvInputService kad Comedia postane dostupna; ponavlja tune ako je cekao na middleware. */
    void onMiddlewareReady() {
        if (mPendingChannelUri != null) {
            Log.d(TAG, "onMiddlewareReady: ponavljam pending tune " + mPendingChannelUri);
            Uri uri = mPendingChannelUri;
            mPendingChannelUri = null;
            mTuneHandler.post(() -> doTune(uri));
        }
    }

    @Override
    public boolean onSetSurface(Surface surface) {
        Log.d(TAG, "onSetSurface: " + surface);
        mSurface = surface;
        return true;
    }

    @Override
    public void onSetStreamVolume(float volume) {
        Log.d(TAG, "onSetStreamVolume: " + volume);
        mStreamVolume = volume;
    }

    @Override
    public void onSetCaptionEnabled(boolean enabled) {
    }

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

    /** Otkriva video kad MW potvrdi uspesnu promenu kanala; idempotentno (poziva se i iz channelChangeStatus i iz safeToUnblank). */
    private void revealVideo() {
        if (mVideoRevealed) {
            return;
        }
        mVideoRevealed = true;
        notifyVideoAvailable();
        // Audio komponente su spremne tek nakon potvrde promene kanala — tek sad gradi listu traka.
        publishAudioTracks();
    }

    /** Cita audio trake aktivne rute iz MW-a i objavljuje ih kroz TIF (sistemski Audio meni). Mora na main thread-u. */
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

    /** Mapira A4TV konfiguraciju kanala u broj kanala za TvTrackInfo (0 = nepoznato, ne postavlja se). */
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
