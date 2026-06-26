package com.example.broadcastlivetvapplication;

import android.media.tv.TvInputService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** TIS koordinator: povezuje ComediaMiddlewareConnection, ChannelScanner i ChannelPublisher (vidi brodcast/Service_installation/). */
public class DtvTvInputService extends TvInputService {

    private static final String TAG = "DtvTvInputService";

    public interface ScanResultListener {
        void onScanProgress(int sourceIndex, int sourceCount, String sourceUrl);
        void onScanFinished(int channelCount);
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

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mScanSourceUrls = StreamsXmlReader.readScanSourceUrls(this);

        mMiddlewareConnection = new ComediaMiddlewareConnection(this, new ComediaMiddlewareConnection.Listener() {
            @Override
            public void onMiddlewareAvailable() {
                Log.d(TAG, "Comedia middleware available");
            }

            @Override
            public void onMiddlewareUnavailable() {
                Log.d(TAG, "Comedia middleware unavailable");
            }
        });
    }

    public void setScanResultListener(@Nullable ScanResultListener listener) {
        mScanResultListener = listener;
    }

    public void startScan(@NonNull String inputId) {
        if (!mMiddlewareConnection.isAvailable()) {
            notifyError("Comedia middleware not connected");
            return;
        }

        mCurrentInputId = inputId;
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
                    public void onScanFinished() {
                        ChannelPublisher publisher = new ChannelPublisher(getContentResolver(), mMiddlewareConnection.getServiceControl());
                        int count = publisher.publishInstalledServices(mCurrentInputId);
                        if (mScanResultListener != null) {
                            mScanResultListener.onScanFinished(count);
                        }
                    }

                    @Override
                    public void onScanError(String reason) {
                        notifyError(reason);
                    }
                });
        mChannelScanner.startScan(mScanSourceUrls);
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
        return null;
    }
}
