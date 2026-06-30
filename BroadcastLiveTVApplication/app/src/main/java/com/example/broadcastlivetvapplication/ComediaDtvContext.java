package com.example.broadcastlivetvapplication;

import android.content.Context;
import android.util.Log;

import iwedia.dtv.DtvContext;

/**
 * Prosiruje DtvContext i preusmerava dogadjaje dostupnosti Comedia middleware-a
 * na {@link AvailabilityListener}, odvajajuci MW lifecycle od ostatka aplikacije.
 */
public class ComediaDtvContext extends DtvContext {

    private static final String TAG = "ComediaDtvContext";

    /** Callback za pracenje promene dostupnosti Comedia middleware-a. */
    public interface AvailabilityListener {
        /** Pozvano kada MW postane dostupan i spreman za upotrebu. */
        void onDtvAvailable();

        /** Pozvano kada MW postane nedostupan (npr. restart servisa). */
        void onDtvUnavailable();
    }

    private final AvailabilityListener mListener;

    /**
     * @param context  Android context (tipicno Application ili Service)
     * @param listener prima obavesti o promeni dostupnosti MW-a
     */
    public ComediaDtvContext(Context context, AvailabilityListener listener) {
        super(context);
        mListener = listener;
    }

    /**
     * Pozvano od DtvContext kada MW postane dostupan; prosledjuje dogadjaj listeneru.
     */
    @Override
    protected void onDtvAvailable() {
        Log.d(TAG, "Comedia middleware connected");
        mListener.onDtvAvailable();
    }

    /**
     * Pozvano od DtvContext kada MW postane nedostupan; prosledjuje dogadjaj listeneru.
     */
    @Override
    protected void onDtvUnavailable() {
        Log.d(TAG, "Comedia middleware disconnected");
        mListener.onDtvUnavailable();
    }
}
