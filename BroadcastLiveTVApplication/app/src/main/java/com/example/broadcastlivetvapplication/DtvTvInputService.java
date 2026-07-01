package com.example.broadcastlivetvapplication;

import android.media.tv.TvInputService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Glavna TvInputService implementacija (TIS koordinator).
 * Povezuje {@link ComediaMiddlewareConnection}, {@link ChannelScanner} i {@link ChannelPublisher}.
 * Odgovorna za inicijalni scan kanala i kreiranje sesija za zapping.
 * Vidi: brodcast/Service_installation/
 */
public class DtvTvInputService extends TvInputService {

    private static final String TAG = "DtvTvInputService";

    /** Callback za pracenje toka i rezultata skeniranja kanala (tipicno koristi SetupActivity). */
    public interface ScanResultListener {
        /**
         * Pozvano pre pocetka skena jednog izvora.
         *
         * @param sourceIndex redni broj trenutnog izvora (od 1)
         * @param sourceCount ukupan broj izvora
         * @param sourceUrl   URL izvora koji se skenira
         */
        void onScanProgress(int sourceIndex, int sourceCount, String sourceUrl);

        /**
         * Pozvano kada su svi novi izvori uspesno skenirani i kanali upisani.
         *
         * @param channelCount ukupan broj upisanih kanala
         */
        void onScanFinished(int channelCount);

        /**
         * Pozvano umesto {@link #onScanFinished} kada su svi izvori iz streams.xml
         * vec bili skenirani — MW nije ni dirnut.
         */
        void onAllSourcesAlreadyScanned();

        /**
         * Pozvano ako scan nije mogao da se pokrene ili je prekinut.
         *
         * @param reason opis greske za prikaz korisniku
         */
        void onScanError(String reason);
    }

    @Nullable
    private static DtvTvInputService sInstance;

    /**
     * Vraca trenutnu instancu servisa (singleton po procesu).
     *
     * @return instanca servisa, ili {@code null} ako servis jos nije kreiran
     */
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
    private EpgPublisher mEpgPublisher;

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

    /**
     * Pravi {@link ChannelScanner} i {@link ChannelPublisher} ako jos ne postoje.
     * Isti scanner se koristi i za inicijalni scan i za rescan pri tune-u.
     */
    private void ensureChannelScanner() {
        if (mChannelScanner != null) {
            return;
        }
        mChannelPublisher = new ChannelPublisher(getContentResolver(), mMiddlewareConnection.getServiceControl());
        EpgProgramWriter epgWriter = new EpgProgramWriter(getContentResolver());
        mEpgPublisher = new EpgPublisher(mMiddlewareConnection.getEpgControl(), epgWriter);
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
                        EpgChannelMapper mapper = new EpgChannelMapper(
                                getContentResolver(), mMiddlewareConnection.getServiceControl());
                        SparseArray<Long> channelMap = mapper.buildServiceIndexToChannelId(mCurrentInputId);
                        mEpgPublisher.publishAll(channelMap);
                    }

                    @Override
                    public void onScanError(String reason) {
                        notifyError(reason);
                    }
                });
    }

    /**
     * Pozvano iz {@link DtvTvInputSession#onRelease()} kada sesija bude oslobodjena.
     *
     * @param session sesija koja je oslobodjena
     */
    void onSessionReleased(DtvTvInputSession session) {
        if (mActiveSession == session) {
            mActiveSession = null;
        }
    }

    /**
     * Registruje ili uklanja listener za pracenje toka skeniranja.
     *
     * @param listener listener koji prima callback-ove, ili {@code null} za odjavu
     */
    public void setScanResultListener(@Nullable ScanResultListener listener) {
        mScanResultListener = listener;
    }

    /**
     * Vraca deljenu instancu {@link ChannelScanner}-a.
     * Koriste je i inicijalni scan i rescan pri tune-u.
     *
     * @return scanner, ili {@code null} ako MW jos nije bio dostupan
     */
    @Nullable
    ChannelScanner getChannelScanner() {
        return mChannelScanner;
    }

    /**
     * Pokrece scan koristeci izvore iz bundlovanog {@code res/xml/streams.xml}.
     * Fallback kada korisnik ne unese putanju u SetupActivity.
     *
     * @param inputId TIF input ID za koji se kanali upisuju
     */
    public void startScan(@NonNull String inputId) {
        startScan(inputId, mScanSourceUrls);
    }

    /**
     * Pokrece scan koristeci izvore iz streams.xml fajla na filesystem putanji uredjaja.
     *
     * @param inputId        TIF input ID za koji se kanali upisuju
     * @param streamsXmlPath apsolutna putanja do streams.xml na uredjaju (npr. /data/streams.xml)
     */
    public void startScan(@NonNull String inputId, @NonNull String streamsXmlPath) {
        String[] sourceUrls = StreamsXmlReader.readScanSourceUrlsFromFile(streamsXmlPath);
        if (sourceUrls.length == 0) {
            notifyError("streams.xml at " + streamsXmlPath + " has no <input> entries or could not be read");
            return;
        }
        startScan(inputId, sourceUrls);
    }

    /**
     * Interna implementacija: filtrira vec skenirane izvore i pokrece scanner samo za nove.
     *
     * @param inputId    TIF input ID
     * @param sourceUrls svi URL-ovi kandidati za skeniranje
     */
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

    /**
     * Loguje gresku i prosledjuje je listeneru ako postoji.
     *
     * @param reason opis greske
     */
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

    /**
     * Kreira novu TIF sesiju za dati inputId.
     *
     * @param inputId TIF input ID
     * @return nova {@link DtvTvInputSession} instanca
     */
    @Nullable
    @Override
    public Session onCreateSession(@NonNull String inputId) {
        mActiveSession = new DtvTvInputSession(this, this, mMiddlewareConnection);
        return mActiveSession;
    }
}
