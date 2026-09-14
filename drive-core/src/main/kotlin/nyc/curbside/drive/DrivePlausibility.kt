package nyc.curbside.drive

import java.time.Duration

/**
 * The last check before a parking spot is believed: did a car actually go anywhere?
 *
 * [DriveStateMachine] decides a drive ended from signals alone, and its one guard against a drive
 * that never happened is duration — under ninety seconds is someone sitting in the car with the
 * radio on. That misses the failure this exists for.
 *
 * Activity recognition sometimes calls a walk a vehicle trip. When it does, the app drops a fresh
 * pin wherever the walk ended, several hundred metres from the car, and it replaces the correct
 * spot recorded minutes earlier. Observed on 2026-09-14: a parking at 08:49:57, then a second one
 * at 08:54:17 exactly 369 metres away — 1.4 m/s, which is walking, not driving.
 *
 * By the time a fix has been taken the app knows where the drive began and where it ended, so it
 * can check the one thing the signals cannot say: the speed implied over the whole trip. A car that
 * averaged walking pace between the two did not drive anywhere.
 */
object DrivePlausibility {

    /**
     * Slower than this, averaged from where the drive began to where it ended, and it was a walk.
     *
     * Brisk walking is about 1.5 m/s and a jog 3. Traffic can crawl, but a car averaging under
     * 7 km/h door to door over a trip this short is doing something other than driving.
     */
    const val MIN_AVERAGE_SPEED_MPS: Double = 2.0

    /**
     * Only trips shorter than this are second-guessed.
     *
     * Deliberately short. Discarding a parking is destructive — the car really is somewhere new and
     * the app will keep pointing at the old spot — so this only covers distances a person plausibly
     * walks while their phone calls it driving. The case this exists for covered 369 metres; a
     * drive of half a kilometre or more is never second-guessed however slowly it went.
     *
     * It was a kilometre, and that ate a real drive.
     */
    const val MAX_SUSPECT_DISTANCE_METERS: Double = 500.0

    /**
     * True when this "drive" looks like somebody walking.
     *
     * @param distanceMeters from where the drive began to where it ended. Null when the phone had
     *   no usable position at the start, in which case there is nothing to check and the parking
     *   stands.
     * @param endedBy which signal called the end of the drive. A car stereo unpairing or an Android
     *   Auto head unit disconnecting is hard evidence that a car was involved at all, so neither is
     *   ever second-guessed here — only activity recognition, which is the one that misfires.
     */
    fun looksLikeWalking(
        distanceMeters: Double?,
        duration: Duration?,
        endedBy: SignalSource,
    ): Boolean {
        if (endedBy != SignalSource.ACTIVITY_RECOGNITION) return false
        if (distanceMeters == null || duration == null) return false
        if (distanceMeters > MAX_SUSPECT_DISTANCE_METERS) return false

        val seconds = duration.seconds
        if (seconds <= 0) return false

        return distanceMeters / seconds < MIN_AVERAGE_SPEED_MPS
    }
}
