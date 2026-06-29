package com.example.broadcastlivetvapplication;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.media.tv.TvInputInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

public class SetupActivity extends Activity {

    private String mInputId;
    private DtvTvInputService mService;
    private TextView mStatusText;
    private EditText mStreamsXmlPathInput;
    private ProgressBar mScanProgressBar;

    private final DtvTvInputService.ScanResultListener mScanResultListener =
            new DtvTvInputService.ScanResultListener() {
        @Override
        public void onScanProgress(int sourceIndex, int sourceCount, String sourceUrl) {
            runOnUiThread(() -> {
                mStatusText.setText(getString(R.string.setup_scan_progress, sourceIndex, sourceCount, sourceUrl));
                mScanProgressBar.setProgress(sourceIndex * 100 / sourceCount);
            });
        }

        @Override
        public void onScanFinished(int channelCount) {
            runOnUiThread(() -> {
                mStatusText.setText(getString(R.string.setup_scan_complete, channelCount));
                mScanProgressBar.setProgress(100);
                mScanProgressBar.getProgressDrawable().setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN);
                setResult(RESULT_OK);
            });
        }

        @Override
        public void onAllSourcesAlreadyScanned() {
            runOnUiThread(() -> {
                mStatusText.setText(R.string.setup_scan_already_done);
                mScanProgressBar.setProgress(100);
                mScanProgressBar.getProgressDrawable().setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN);
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
            mService = DtvTvInputService.getInstance();
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
        mStreamsXmlPathInput = findViewById(R.id.streams_xml_path);
        mScanProgressBar = findViewById(R.id.scan_progress_bar);

        Button scanButton = findViewById(R.id.scan_button);
        scanButton.setOnClickListener(v -> startScan());

        bindService(new Intent(this, DtvTvInputService.class), mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void startScan() {
        if (mService == null || mInputId == null) {
            return;
        }
        mStatusText.setText(R.string.setup_scanning);
        mScanProgressBar.setVisibility(View.VISIBLE);
        mScanProgressBar.setProgress(0);
        mScanProgressBar.getProgressDrawable().clearColorFilter();

        String streamsXmlPath = mStreamsXmlPathInput.getText().toString().trim();
        if (TextUtils.isEmpty(streamsXmlPath)) {
            mService.startScan(mInputId);
        } else {
            mService.startScan(mInputId, streamsXmlPath);
        }
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
