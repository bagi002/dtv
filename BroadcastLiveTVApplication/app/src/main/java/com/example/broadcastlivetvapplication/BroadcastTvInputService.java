package com.example.broadcastlivetvapplication;

import android.content.ContentValues;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.iwedia.dtv.A4TVStatus;
import com.iwedia.dtv.comediaroutemanager.RouteManagerMediumType;
import com.iwedia.dtv.scan.ScanInstallStatus;
import com.iwedia.dtv.service.ServiceDescriptor;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import iwedia.dtv.comediaroutemanager.ComediaRouteManagerControl;
import iwedia.dtv.scan.ScanControl;
import iwedia.dtv.scan.ScanListener;
import iwedia.dtv.service.ServiceControl;

public class BroadcastTvInputService extends TvInputService {

    private static final String TAG = "BroadcastTvInputService";

    /** Izvori za scan se ucitavaju iz res/xml/streams.xml (vidi UML "Tuner SDK/HAL - demonstration"). */
    private String[] mScanSourceUrls = new String[0];

    /** Parsira res/xml/streams.xml i vraca <input> vrednosti svih <channel> elemenata. */
    private String[] loadScanSourceUrls() {
        List<String> urls = new ArrayList<>();
        try (XmlResourceParser parser = getResources().getXml(R.xml.streams)) {
            int eventType = parser.getEventType();
            boolean insideInput = false;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && "input".equals(parser.getName())) {
                    insideInput = true;
                } else if (eventType == XmlPullParser.TEXT && insideInput) {
                    String text = parser.getText();
                    if (text != null && !text.trim().isEmpty()) {
                        urls.add(text.trim());
                    }
                } else if (eventType == XmlPullParser.END_TAG && "input".equals(parser.getName())) {
                    insideInput = false;
                }
                eventType = parser.next();
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Failed to parse streams.xml", e);
        }
        return urls.toArray(new String[0]);
    }

    public interface ScanResultListener {
        void onScanProgress(int sourceIndex, int sourceCount, String sourceUrl);
        void onScanFinished(int channelCount);
        void onScanError(String reason);
    }

    @Nullable
    private static BroadcastTvInputService sInstance;

    @Nullable
    public static BroadcastTvInputService getInstance() {
        return sInstance;
    }

    private BroadcastDtvContext mDtvContext;
    private ComediaRouteManagerControl mRouteManagerControl;
    private ScanControl mScanControl;
    private ServiceControl mServiceControl;

    private ScanResultListener mScanResultListener;
    private String mCurrentInputId;
    private int mInstallRouteId = -1;
    private int mNextScanSourceIndex = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mScanSourceUrls = loadScanSourceUrls();

        mDtvContext = new BroadcastDtvContext(this, new BroadcastDtvContext.AvailabilityListener() {
            @Override
            public void onDtvAvailable() {
                try {
                    mRouteManagerControl = new ComediaRouteManagerControl(mDtvContext);
                    Log.d(TAG, "new ComediaRouteManagerControl() -> " + mRouteManagerControl);
                } catch (Throwable t) {
                    Log.e(TAG, "new ComediaRouteManagerControl() threw", t);
                    mRouteManagerControl = null;
                }
                try {
                    mScanControl = new ScanControl(mDtvContext);
                    Log.d(TAG, "new ScanControl() -> " + mScanControl);
                } catch (Throwable t) {
                    Log.e(TAG, "new ScanControl() threw", t);
                    mScanControl = null;
                }
                try {
                    mServiceControl = new ServiceControl(mDtvContext);
                    Log.d(TAG, "new ServiceControl() -> " + mServiceControl);
                } catch (Throwable t) {
                    Log.e(TAG, "new ServiceControl() threw", t);
                    mServiceControl = null;
                }
            }

            @Override
            public void onDtvUnavailable() {
                mRouteManagerControl = null;
                mScanControl = null;
                mServiceControl = null;
            }
        });
    }

    public void setScanResultListener(@Nullable ScanResultListener listener) {
        mScanResultListener = listener;
    }

    public void startScan(@NonNull String inputId) {
        if (mRouteManagerControl == null || mScanControl == null) {
            notifyError("Comedia middleware not connected");
            return;
        }

        mCurrentInputId = inputId;
        mInstallRouteId = mRouteManagerControl.getInstallRoute(RouteManagerMediumType.MEDIUM_IP);
        Log.d(TAG, "startScan inputId=" + inputId + " installRouteId=" + mInstallRouteId);

        if (mInstallRouteId == 0) {
            notifyError("getInstallRoute failed (no free route resource)");
            return;
        }

        A4TVStatus registerStatus = mScanControl.registerListener(mScanListener);
        if (registerStatus != A4TVStatus.SUCCESS) {
            notifyError("registerListener failed: " + registerStatus);
            return;
        }

        mNextScanSourceIndex = 0;
        scanNextSource();
    }

    /** Pokrece autoScan za sledeci izvor iz mScanSourceUrls; append=false samo za prvi (brise staru listu). */
    private void scanNextSource() {
        boolean isFirstSource = mNextScanSourceIndex == 0;
        String sourceUrl = mScanSourceUrls[mNextScanSourceIndex];

        mScanControl.appendList(!isFirstSource);

        Log.d(TAG, "scanNextSource: index=" + mNextScanSourceIndex + " url=" + sourceUrl);
        if (mScanResultListener != null) {
            mScanResultListener.onScanProgress(mNextScanSourceIndex + 1, mScanSourceUrls.length, sourceUrl);
        }
        A4TVStatus status = mScanControl.autoScan(mInstallRouteId, sourceUrl);
        if (status != A4TVStatus.SUCCESS) {
            mScanControl.unregisterListener(mScanListener);
            notifyError("autoScan failed for " + sourceUrl + ": " + status);
        }
    }

    private final ScanListener mScanListener = new ScanListener() {
        @Override
        public void installStatus(ScanInstallStatus status) {
            Log.d(TAG, "installStatus routeId=" + status.getInstallRouteId());
        }

        @Override
        public void scanTunFrequency(int routeId, int frequency) {
        }

        @Override
        public void scanFinished(int routeId) {
            Log.d(TAG, "scanFinished routeId=" + routeId + " source=" + mScanSourceUrls[mNextScanSourceIndex]);
            mNextScanSourceIndex++;
            if (mNextScanSourceIndex < mScanSourceUrls.length) {
                scanNextSource();
                return;
            }

            mScanControl.unregisterListener(mScanListener);
            int count = publishInstalledServices(mCurrentInputId);
            if (mScanResultListener != null) {
                mScanResultListener.onScanFinished(count);
            }
        }

        @Override
        public void scanAborted(int routeId, int broadcastType) {
            Log.e(TAG, "scanAborted routeId=" + routeId + " broadcastType=" + broadcastType);
            mScanControl.unregisterListener(mScanListener);
            notifyError("scan aborted: " + broadcastType);
        }

        @Override
        public void installServiceTVName(int routeId, String name) {
            Log.d(TAG, "installServiceTVName: " + name);
        }

        @Override
        public void installServiceRADIOName(int routeId, String name) {
        }

        @Override
        public void installServiceDATAName(int routeId, String name) {
        }

        @Override
        public void installServiceTVNumber(int routeId, int number) {
            Log.d(TAG, "installServiceTVNumber: " + number);
        }

        @Override
        public void installServiceRADIONumber(int routeId, int number) {
        }

        @Override
        public void installServiceDATANumber(int routeId, int number) {
        }

        @Override
        public void scanProgressChanged(int routeId, int value) {
        }

        @Override
        public void antennaConnected(int routeId, boolean state) {
        }

        @Override
        public void signalQuality(int routeId, int quality) {
        }

        @Override
        public void signalStrength(int routeId, int strength) {
        }

        @Override
        public void signalBer(int routeId, int ber) {
        }

        @Override
        public void scanNoServiceSpace(int routeId) {
        }

        @Override
        public void tunerLocked(int id, boolean locked) {
            Log.d(TAG, "tunerLocked id=" + id + " locked=" + locked);
        }

        @Override
        public void networkChanged(int networkId) {
        }

        @Override
        public void sat2ipServerDropped(int routeId) {
        }

        @Override
        public void triggerStatus(int routeId) {
        }

        @Override
        public void signalLost() {
        }

        @Override
        public void signalReturned() {
        }
    };

    private static final int SERVICE_LIST_INDEX = 0;

    /** TIS cita rezultate iz Service & Mux DB preko ServiceControl i upisuje ih u TvContract.Channels. */
    private int publishInstalledServices(String inputId) {
        if (mServiceControl == null || inputId == null) {
            return 0;
        }

        int count = mServiceControl.getServiceListCount(SERVICE_LIST_INDEX);
        for (int i = 0; i < count; i++) {
            ServiceDescriptor descriptor = mServiceControl.getServiceDescriptor(SERVICE_LIST_INDEX, i);
            if (descriptor != null) {
                upsertChannel(inputId, descriptor);
            }
        }
        return count;
    }

    private void upsertChannel(String inputId, ServiceDescriptor descriptor) {
        ContentValues values = new ContentValues();
        values.put(TvContract.Channels.COLUMN_INPUT_ID, inputId);
        values.put(TvContract.Channels.COLUMN_DISPLAY_NAME, descriptor.getName());
        values.put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, String.valueOf(descriptor.getServiceId()));
        values.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_DVB_T);
        values.put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO);
        values.put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, descriptor.getONID());
        values.put(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID, descriptor.getTSID());
        values.put(TvContract.Channels.COLUMN_SERVICE_ID, descriptor.getServiceId());

        Long existingChannelId = findChannelId(inputId, descriptor.getONID(), descriptor.getTSID(), descriptor.getServiceId());
        if (existingChannelId != null) {
            getContentResolver().update(
                    TvContract.buildChannelUri(existingChannelId), values, null, null);
        } else {
            getContentResolver().insert(TvContract.Channels.CONTENT_URI, values);
        }
    }

    @Nullable
    private Long findChannelId(String inputId, int onid, int tsid, int serviceId) {
        Uri uri = TvContract.buildChannelsUriForInput(inputId);
        String[] projection = {
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
                TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
                TvContract.Channels.COLUMN_SERVICE_ID,
        };
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                if (cursor.getInt(1) == onid && cursor.getInt(2) == tsid && cursor.getInt(3) == serviceId) {
                    return cursor.getLong(0);
                }
            }
        }
        return null;
    }

    private void notifyError(String reason) {
        Log.e(TAG, reason);
        if (mScanResultListener != null) {
            mScanResultListener.onScanError(reason);
        }
    }

    @Override
    public void onDestroy() {
        if (mScanControl != null) {
            mScanControl.unregisterListener(mScanListener);
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
