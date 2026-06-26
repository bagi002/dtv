package com.example.broadcastlivetvapplication;

import android.content.Context;
import android.util.Log;

import iwedia.dtv.DtvContext;

public class ComediaDtvContext extends DtvContext {

    private static final String TAG = "ComediaDtvContext";

    public interface AvailabilityListener {
        void onDtvAvailable();
        void onDtvUnavailable();
    }

    private final AvailabilityListener mListener;

    public ComediaDtvContext(Context context, AvailabilityListener listener) {
        super(context);
        mListener = listener;
    }

    @Override
    protected void onDtvAvailable() {
        Log.d(TAG, "Comedia middleware connected");
        mListener.onDtvAvailable();
    }

    @Override
    protected void onDtvUnavailable() {
        Log.d(TAG, "Comedia middleware disconnected");
        mListener.onDtvUnavailable();
    }
}
