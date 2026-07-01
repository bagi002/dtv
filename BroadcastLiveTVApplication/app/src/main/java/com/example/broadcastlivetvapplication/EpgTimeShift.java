package com.example.broadcastlivetvapplication;

import com.iwedia.dtv.epg.EpgEvent;

import java.util.List;

/**
 * Pomera EPG evente iz prošlosti u sadašnjost/budućnost.
 *
 * Pošto .ts fajlovi nose EPG sa vremenima u prošlosti (vreme kreiranja fajla),
 * a LiveTv prikazuje samo sadašnje i buduće programe, svi eventi se pomeraju
 * za isti offset tako da najraniji počinje od sada. Redosled i relativni
 * razmaci između programa ostaju nepromenjeni.
 */
final class EpgTimeShift {

    private EpgTimeShift() {
    }

    /**
     * Izračunava offset koji treba dodati svim eventima da bi najraniji
     * počinjao od trenutnog sistemskog vremena.
     *
     * @param events lista evenata (neprazna)
     * @return offset u milisekundama
     */
    static long computeOffset(List<EpgEvent> events) {
        long earliestMs = findEarliestStartMs(events);
        return System.currentTimeMillis() - earliestMs;
    }

    /**
     * Vraća pomereno vreme početka eventa u milisekundama.
     *
     * @param event  EPG event
     * @param offset offset dobijen iz {@link #computeOffset}
     * @return pomereni start u ms (UTC)
     */
    static long shiftedStartMs(EpgEvent event, long offset) {
        return event.getStartTime().getCalendar().getTimeInMillis() + offset;
    }

    /**
     * Vraća pomereno vreme kraja eventa u milisekundama.
     *
     * @param event  EPG event
     * @param offset offset dobijen iz {@link #computeOffset}
     * @return pomereni end u ms (UTC)
     */
    static long shiftedEndMs(EpgEvent event, long offset) {
        return event.getEndTime().getCalendar().getTimeInMillis() + offset;
    }

    private static long findEarliestStartMs(List<EpgEvent> events) {
        long earliest = Long.MAX_VALUE;
        for (EpgEvent event : events) {
            long ms = event.getStartTime().getCalendar().getTimeInMillis();
            if (ms < earliest) {
                earliest = ms;
            }
        }
        return earliest;
    }
}
