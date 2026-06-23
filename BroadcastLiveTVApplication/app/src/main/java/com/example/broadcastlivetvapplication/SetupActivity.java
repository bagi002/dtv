package com.example.broadcastlivetvapplication;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.tv.TvInputInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class SetupActivity extends Activity {

    private String mInputId;
    private BroadcastTvInputService mService;
    private TextView mStatusText;

    private final BroadcastTvInputService.ScanResultListener mScanResultListener =
            new BroadcastTvInputService.ScanResultListener() {
        @Override
        public void onScanFinished(int channelCount) {
            runOnUiThread(() -> {
                mStatusText.setText(getString(R.string.setup_scan_complete, channelCount));
                setResult(RESULT_OK);
            });
        }

        @Override
        public void onScanError(String reason) {
            runOnUiThread(() -> mStatusText.setText(getString(R.string.setup_scan_error, reason)));
        }
    };

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = BroadcastTvInputService.getInstance();
            if (mService != null) {
                mService.setScanResultListener(mScanResultListener);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        mInputId = getIntent().getStringExtra(TvInputInfo.EXTRA_INPUT_ID);
        mStatusText = findViewById(R.id.status_text);

        Button scanButton = findViewById(R.id.scan_button);
        scanButton.setOnClickListener(v -> startScan());

        bindService(new Intent(this, BroadcastTvInputService.class), mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void startScan() {
        if (mService == null || mInputId == null) {
            return;
        }
        mStatusText.setText(R.string.setup_scanning);
        mService.startScan(mInputId);
    }

    @Override
    protected void onDestroy() {
        if (mService != null) {
            mService.setScanResultListener(null);
        }
        unbindService(mServiceConnection);
        super.onDestroy();
    }
}
