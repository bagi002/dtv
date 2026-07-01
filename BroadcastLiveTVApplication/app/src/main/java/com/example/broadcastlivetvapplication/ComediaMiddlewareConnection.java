package com.example.broadcastlivetvapplication;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import iwedia.dtv.audio.AudioControl;
import iwedia.dtv.comediaroutemanager.ComediaRouteManagerControl;
import iwedia.dtv.display.DisplayControl;
import iwedia.dtv.epg.EpgControl;
import iwedia.dtv.scan.ScanControl;
import iwedia.dtv.service.ServiceControl;

/**
 * Drzi konekciju ka Comedia middleware-u (preko {@link ComediaDtvContext}) i kreira
 * sve Control instance cim MW postane dostupan. Obavestavava {@link Listener} o promeni
 * dostupnosti, a getter metode vracaju {@code null} dok MW nije spreman.
 */
final class ComediaMiddlewareConnection {

    private static final String TAG = "ComediaMiddlewareConn";

    /** Callback za pracenje dostupnosti MW konekcije. */
    interface Listener {
        /** Pozvano kada su sve Control instance uspesno kreirane i MW je spreman. */
        void onMiddlewareAvailable();

        /** Pozvano kada MW postane nedostupan; sve Control instance su postavljene na {@code null}. */
        void onMiddlewareUnavailable();
    }

    private final ComediaDtvContext mDtvContext;
    private final Listener mListener;

    @Nullable
    private ComediaRouteManagerControl mRouteManagerControl;
    @Nullable
    private ScanControl mScanControl;
    @Nullable
    private ServiceControl mServiceControl;
    @Nullable
    private DisplayControl mDisplayControl;
    @Nullable
    private AudioControl mAudioControl;
    @Nullable
    private EpgControl mEpgControl;

    /**
     * Kreira konekciju i pokrece {@link ComediaDtvContext}; Control instance se prave
     * tek kada MW postane dostupan.
     *
     * @param context  Android context
     * @param listener prima obavesti kada MW postane dostupan ili nedostupan
     */
    ComediaMiddlewareConnection(Context context, Listener listener) {
        mListener = listener;
        mDtvContext = new ComediaDtvContext(context, new ComediaDtvContext.AvailabilityListener() {
            @Override
            public void onDtvAvailable() {
                mRouteManagerControl = createOrNull("ComediaRouteManagerControl", () -> new ComediaRouteManagerControl(mDtvContext));
                mScanControl = createOrNull("ScanControl", () -> new ScanControl(mDtvContext));
                mServiceControl = createOrNull("ServiceControl", () -> new ServiceControl(mDtvContext));
                mDisplayControl = createOrNull("DisplayControl", () -> new DisplayControl(mDtvContext));
                mAudioControl = createOrNull("AudioControl", () -> new AudioControl(mDtvContext));
                mEpgControl = createOrNull("EpgControl", () -> new EpgControl(mDtvContext));
                mListener.onMiddlewareAvailable();
            }

            @Override
            public void onDtvUnavailable() {
                mRouteManagerControl = null;
                mScanControl = null;
                mServiceControl = null;
                mDisplayControl = null;
                mAudioControl = null;
                mEpgControl = null;
                mListener.onMiddlewareUnavailable();
            }
        });
    }

    private interface ControlFactory<T> {
        T create();
    }

    /**
     * Pokusava da kreira Control instancu; hvata sve izuzetke i vraca {@code null} umesto pada.
     *
     * @param label   naziv klase za logovanje
     * @param factory lambda koja kreira instancu
     * @return kreirani objekat, ili {@code null} ako je kreiranje bacilo izuzetak
     */
    @Nullable
    private static <T> T createOrNull(String label, ControlFactory<T> factory) {
        try {
            T control = factory.create();
            Log.d(TAG, "new " + label + "() -> " + control);
            return control;
        } catch (Throwable t) {
            Log.e(TAG, "new " + label + "() threw", t);
            return null;
        }
    }

    /**
     * Proverava da li su sve Control instance uspesno kreirane.
     *
     * @return {@code true} ako je MW spreman za upotrebu
     */
    boolean isAvailable() {
        return mRouteManagerControl != null && mScanControl != null && mServiceControl != null
                && mDisplayControl != null && mAudioControl != null;
    }

    /**
     * Vraca kontroler za live i install rute.
     *
     * @return {@link ComediaRouteManagerControl}, ili {@code null} dok MW nije dostupan
     */
    @Nullable
    ComediaRouteManagerControl getRouteManagerControl() {
        return mRouteManagerControl;
    }

    /**
     * Vraca kontroler za pokretanje autoScan-a.
     *
     * @return {@link ScanControl}, ili {@code null} dok MW nije dostupan
     */
    @Nullable
    ScanControl getScanControl() {
        return mScanControl;
    }

    /**
     * Vraca kontroler za start/stop servisa i registraciju ServiceListener-a.
     *
     * @return {@link ServiceControl}, ili {@code null} dok MW nije dostupan
     */
    @Nullable
    ServiceControl getServiceControl() {
        return mServiceControl;
    }

    /**
     * Vraca kontroler za postavljanje Surface-a i skaliranje video prozora.
     *
     * @return {@link DisplayControl}, ili {@code null} dok MW nije dostupan
     */
    @Nullable
    DisplayControl getDisplayControl() {
        return mDisplayControl;
    }

    /**
     * Vraca kontroler za audio trake i registraciju AudioListener-a.
     *
     * @return {@link AudioControl}, ili {@code null} dok MW nije dostupan
     */
    @Nullable
    AudioControl getAudioControl() {
        return mAudioControl;
    }

    /**
     * Vraca kontroler za citanje EPG podataka (Present/Following i Schedule).
     *
     * @return {@link EpgControl}, ili {@code null} dok MW nije dostupan
     */
    @Nullable
    EpgControl getEpgControl() {
        return mEpgControl;
    }
}
