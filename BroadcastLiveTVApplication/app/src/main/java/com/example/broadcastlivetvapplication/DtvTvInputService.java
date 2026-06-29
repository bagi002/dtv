package com.example.broadcastlivetvapplication;

import android.media.tv.TvInputService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** TIS koordinator: povezuje ComediaMiddlewareConnection, ChannelScanner i ChannelPublisher (vidi brodcast/Service_installation/). */
public class DtvTvInputService extends TvInputService {

    private static final String TAG = "DtvTvInputService";

    public interface ScanResultListener {
        void onScanProgress(int sourceIndex, int sourceCount, String sourceUrl);
        void onScanFinished(int channelCount);
        /** Pozvano umesto onScanFinished kad su svi izvori iz streams.xml vec skenirani (nista nije preskoceno/dirano na middleware-u). */
        void onAllSourcesAlreadyScanned();
        void onScanError(String reason);
    }

    @Nullable
    private static DtvTvInputService sInstance;

    @Nullable
    public static DtvTvInputService getInstance() {
        return sInstance;
    }

    private ComediaMiddlewareConnection mMiddlewareConnection;
    private String[] mScanSourceUrls = new String[0];

    private ScanResultListener mScanResultListener;
    private String mCurrentInputId;
    private ChannelScanner mChannelScanner;
    private ChannelPublisher mChannelPublisher;

    @Nullable
    private DtvTvInputSession mActiveSession;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mScanSourceUrls = StreamsXmlReader.readScanSourceUrls(this);

        mMiddlewareConnection = new ComediaMiddlewareConnection(this, new ComediaMiddlewareConnection.Listener() {
            @Override
            public void onMiddlewareAvailable() {
                Log.d(TAG, "Comedia middleware available");
                // ChannelScanner se pravi cim je middleware dostupan (ne tek na klik "Scan" u Setup UI-u),
                // jer ga sesija koristi za rescanSingleSource pri tune-u nezavisno od toga da li je
                // OVAJ proces ikad pokrenuo inicijalni scan (npr. nakon restarta TIS procesa).
                ensureChannelScanner();
                if (mActiveSession != null) {
                    mActiveSession.onMiddlewareReady();
                }
            }

            @Override
            public void onMiddlewareUnavailable() {
                Log.d(TAG, "Comedia middleware unavailable");
            }
        });
    }

    /** Pravi ChannelScanner instancu ako jos ne postoji; isti se koristi za inicijalni scan i za rescan pri tune-u. */
    private void ensureChannelScanner() {
        if (mChannelScanner != null) {
            return;
        }
        mChannelPublisher = new ChannelPublisher(getContentResolver(), mMiddlewareConnection.getServiceControl());
        mChannelScanner = new ChannelScanner(
                mMiddlewareConnection.getRouteManagerControl(),
                mMiddlewareConnection.getScanControl(),
                new ChannelScanner.ResultListener() {
                    @Override
                    public void onScanProgress(int sourceIndex, int sourceCount, String sourceUrl) {
                        if (mScanResultListener != null) {
                            mScanResultListener.onScanProgress(sourceIndex, sourceCount, sourceUrl);
                        }
                    }

                    @Override
                    public void onSourceScanned(String sourceUrl) {
                        mChannelPublisher.collectScannedServices(sourceUrl);
                    }

                    @Override
                    public void onScanFinished() {
                        int count = mChannelPublisher.publishAll(mCurrentInputId);
                        if (mScanResultListener != null) {
                            mScanResultListener.onScanFinished(count);
                        }
                    }

                    @Override
                    public void onScanError(String reason) {
                        notifyError(reason);
                    }
                });
    }

    void onSessionReleased(DtvTvInputSession session) {
        if (mActiveSession == session) {
            mActiveSession = null;
        }
    }

    public void setScanResultListener(@Nullable ScanResultListener listener) {
        mScanResultListener = listener;
    }

    /** ChannelScanner deljen izmedju inicijalnog scan-a i rescan-a pri tune-u (vidi ensureChannelScanner). */
    @Nullable
    ChannelScanner getChannelScanner() {
        return mChannelScanner;
    }

    /** Skenira izvore iz bundlovanog res/xml/streams.xml (fallback ako korisnik ne unese putanju). */
    public void startScan(@NonNull String inputId) {
        startScan(inputId, mScanSourceUrls);
    }

    /** Skenira izvore procitane sa filesystem putanje na uredjaju (vidi SetupActivity EditText za putanju). */
    public void startScan(@NonNull String inputId, @NonNull String streamsXmlPath) {
        String[] sourceUrls = StreamsXmlReader.readScanSourceUrlsFromFile(streamsXmlPath);
        if (sourceUrls.length == 0) {
            notifyError("streams.xml at " + streamsXmlPath + " has no <input> entries or could not be read");
            return;
        }
        startScan(inputId, sourceUrls);
    }

    private void startScan(@NonNull String inputId, @NonNull String[] sourceUrls) {
        if (!mMiddlewareConnection.isAvailable()) {
            notifyError("Comedia middleware not connected");
            return;
        }

        mCurrentInputId = inputId;
        ensureChannelScanner();

        // Izvori cija su kanali vec upisani za ovaj inputId se ne skeniraju ponovo.
        Set<String> alreadyScanned = mChannelPublisher.readScannedSourceUrls(inputId);
        List<String> newSourceUrls = new ArrayList<>();
        for (String sourceUrl : sourceUrls) {
            if (!alreadyScanned.contains(sourceUrl)) {
                newSourceUrls.add(sourceUrl);
            }
        }
        Log.d(TAG, "startScan: " + (sourceUrls.length - newSourceUrls.size()) + " izvora vec skenirano, "
                + newSourceUrls.size() + " novih za skeniranje");

        if (newSourceUrls.isEmpty()) {
            if (mScanResultListener != null) {
                mScanResultListener.onAllSourcesAlreadyScanned();
            }
            return;
        }

        mChannelScanner.startScan(newSourceUrls.toArray(new String[0]));
    }

    private void notifyError(String reason) {
        Log.e(TAG, reason);
        if (mScanResultListener != null) {
            mScanResultListener.onScanError(reason);
        }
    }

    @Override
    public void onDestroy() {
        if (mChannelScanner != null) {
            mChannelScanner.unregisterListener();
        }
        sInstance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public Session onCreateSession(@NonNull String inputId) {
        mActiveSession = new DtvTvInputSession(this, this, mMiddlewareConnection);
        return mActiveSession;
    }
}
