package com.example.broadcastlivetvapplication;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.media.tv.TvContract;
import android.util.Log;

import com.iwedia.dtv.epg.EpgEvent;

import java.util.List;

/**
 * Upisuje EPG evente u {@link TvContract.Programs}.
 *
 * Svaki poziv {@link #writeEvents} briše stare programe za kanal i upisuje nove,
 * već pomerene u sadašnjost/budućnost (vidi {@link EpgTimeShift}).
 */
final class EpgProgramWriter {

    private static final String TAG = "EpgProgramWriter";

    private final ContentResolver mContentResolver;

    EpgProgramWriter(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    /**
     * Briše sve postojeće programe za kanal, pa upisuje novu listu evenata.
     *
     * @param channelId TvContract channel ID
     * @param events    lista evenata sa već primenjenim time-shiftom (koristiti {@link EpgTimeShift})
     * @param offset    time-shift offset u ms; koristi se za računanje pomerenih vremena
     */
    void writeEvents(long channelId, List<EpgEvent> events, long offset) {
        deleteExistingPrograms(channelId);
        for (EpgEvent event : events) {
            insertProgram(channelId, event, offset);
        }
        Log.d(TAG, "writeEvents: channelId=" + channelId + " count=" + events.size());
    }

    private void deleteExistingPrograms(long channelId) {
        mContentResolver.delete(
                TvContract.buildProgramsUriForChannel(channelId), null, null);
    }

    private void insertProgram(long channelId, EpgEvent event, long offset) {
        long startMs = EpgTimeShift.shiftedStartMs(event, offset);
        long endMs   = EpgTimeShift.shiftedEndMs(event, offset);

        ContentValues values = new ContentValues();
        values.put(TvContract.Programs.COLUMN_CHANNEL_ID, channelId);
        values.put(TvContract.Programs.COLUMN_TITLE, AribDecoder.decode(event.getName()));
        values.put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, AribDecoder.decode(event.getDescription()));
        values.put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, startMs);
        values.put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, endMs);

        mContentResolver.insert(TvContract.Programs.CONTENT_URI, values);
    }
}
