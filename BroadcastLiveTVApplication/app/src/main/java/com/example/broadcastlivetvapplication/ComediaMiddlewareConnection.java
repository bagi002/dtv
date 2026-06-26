package com.example.broadcastlivetvapplication;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import iwedia.dtv.comediaroutemanager.ComediaRouteManagerControl;
import iwedia.dtv.scan.ScanControl;
import iwedia.dtv.service.ServiceControl;

/** Drzi konekciju ka Comedia middleware-u (preko ComediaDtvContext) i instance Control klasa koje od nje zavise. */
final class ComediaMiddlewareConnection {

    private static final String TAG = "ComediaMiddlewareConn";

    interface Listener {
        void onMiddlewareAvailable();
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

    ComediaMiddlewareConnection(Context context, Listener listener) {
        mListener = listener;
        mDtvContext = new ComediaDtvContext(context, new ComediaDtvContext.AvailabilityListener() {
            @Override
            public void onDtvAvailable() {
                mRouteManagerControl = createOrNull("ComediaRouteManagerControl", () -> new ComediaRouteManagerControl(mDtvContext));
                mScanControl = createOrNull("ScanControl", () -> new ScanControl(mDtvContext));
                mServiceControl = createOrNull("ServiceControl", () -> new ServiceControl(mDtvContext));
                mListener.onMiddlewareAvailable();
            }

            @Override
            public void onDtvUnavailable() {
                mRouteManagerControl = null;
                mScanControl = null;
                mServiceControl = null;
                mListener.onMiddlewareUnavailable();
            }
        });
    }

    private interface ControlFactory<T> {
        T create();
    }

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

    boolean isAvailable() {
        return mRouteManagerControl != null && mScanControl != null && mServiceControl != null;
    }

    @Nullable
    ComediaRouteManagerControl getRouteManagerControl() {
        return mRouteManagerControl;
    }

    @Nullable
    ScanControl getScanControl() {
        return mScanControl;
    }

    @Nullable
    ServiceControl getServiceControl() {
        return mServiceControl;
    }
}
