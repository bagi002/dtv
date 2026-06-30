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

/**
 * Ekran za podesavanje TIF ulaza.
 * Korisnik moze da unese putanju do streams.xml fajla na uredjaju i pokrene skeniranje kanala.
 * Ako putanja nije unesena, koristi se bundlovani {@code res/xml/streams.xml}.
 * Rezultati skena prikazuju se kroz status tekst i progress bar.
 */
public class SetupActivity extends Activity {

    private String mInputId;
    private DtvTvInputService mService;
    private TextView mStatusText;
    private EditText mStreamsXmlPathInput;
    private ProgressBar mScanProgressBar;

    private final DtvTvInputService.ScanResultListener mScanResultListener =
            new DtvTvInputService.ScanResultListener() {
        /**
         * @param sourceIndex redni broj izvora koji se trenutno skenira (od 1)
         * @param sourceCount ukupan broj izvora
         * @param sourceUrl   URL izvora koji se skenira
         */
        @Override
        public void onScanProgress(int sourceIndex, int sourceCount, String sourceUrl) {
            runOnUiThread(() -> {
                mStatusText.setText(getString(R.string.setup_scan_progress, sourceIndex, sourceCount, sourceUrl));
                mScanProgressBar.setProgress(sourceIndex * 100 / sourceCount);
            });
        }

        /**
         * @param channelCount ukupan broj upisanih kanala
         */
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

        /**
         * @param reason opis greske koji se prikazuje korisniku
         */
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

    /**
     * Inicijalizuje UI, cita inputId iz Intent-a i vezuje se za {@link DtvTvInputService}.
     *
     * @param savedInstanceState sacuvano stanje (nije korisceno)
     */
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

    /**
     * Pokrece skeniranje kanala kada korisnik pritisne dugme.
     * Koristi putanju iz EditText-a ako je unesena; inace fallback na bundlovani streams.xml.
     */
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

    /**
     * Odjavljuje scan listener i razvezuje servis kako ne bi ostao memory leak.
     */
    @Override
    protected void onDestroy() {
        if (mService != null) {
            mService.setScanResultListener(null);
        }
        unbindService(mServiceConnection);
        super.onDestroy();
    }
}
