package com.example.broadcastlivetvapplication;

import android.content.Context;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

/** Session-strana zappinga: drzi Surface/volume i delegira na ChannelLookup + ChannelZapper (vidi brodcast/Zapping/zapping_broadcast.puml). */
final class DtvTvInputSession extends TvInputService.Session {

    private static final String TAG = "DtvTvInputSession";

    private final Context mContext;
    private final DtvTvInputService mService;
    private final ComediaMiddlewareConnection mMiddlewareConnection;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private Surface mSurface;
    private float mStreamVolume = 1.0f;
    private ChannelZapper mZapper;
    private boolean mVideoRevealed;
    private Uri mPendingChannelUri;

    DtvTvInputSession(Context context, DtvTvInputService service, ComediaMiddlewareConnection middlewareConnection) {
        super(context);
        mContext = context;
        mService = service;
        mMiddlewareConnection = middlewareConnection;
    }

    /** Poziva DtvTvInputService kad Comedia postane dostupna; ponavlja tune ako je cekao na middleware. */
    void onMiddlewareReady() {
        if (mPendingChannelUri != null) {
            Log.d(TAG, "onMiddlewareReady: ponavljam pending tune " + mPendingChannelUri);
            Uri uri = mPendingChannelUri;
            mPendingChannelUri = null;
            doTune(uri);
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
        doTune(channelUri);
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
                mMainHandler.post(() -> {
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
                mMainHandler.post(() ->
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN));
            }
        });
    }

    private void startZapWithResult(ChannelLookup.Result result) {
        if (mZapper == null) {
            mZapper = new ChannelZapper(
                    mMiddlewareConnection.getRouteManagerControl(),
                    mMiddlewareConnection.getDisplayControl(),
                    mMiddlewareConnection.getServiceControl(),
                    new ChannelZapper.ResultListener() {
                        @Override
                        public void onChannelChanged(int liveRoute) {
                            Log.d(TAG, "onChannelChanged liveRoute=" + liveRoute);
                            // Neke verzije MW-a ne emituju safeToUnblank; otkrivamo video i ovde (idempotentno).
                            mMainHandler.post(DtvTvInputSession.this::revealVideo);
                        }

                        @Override
                        public void onSafeToUnblank(int liveRoute) {
                            Log.d(TAG, "onSafeToUnblank liveRoute=" + liveRoute);
                            mMainHandler.post(DtvTvInputSession.this::revealVideo);
                        }

                        @Override
                        public void onZapError(String reason) {
                            Log.e(TAG, "onZapError: " + reason);
                            mMainHandler.post(() ->
                                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN));
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
    }

    @Override
    public void onRelease() {
        if (mZapper != null) {
            mZapper.stopZap();
        }
        mService.onSessionReleased(this);
    }
}
