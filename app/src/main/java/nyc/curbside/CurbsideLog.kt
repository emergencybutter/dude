package nyc.curbside

import android.util.Log

/**
 * One tag for the whole app, so a drive can be followed end to end with `adb logcat -s Curbside`.
 *
 * Detection is a chain of separate wake-ups — a broadcast here, an alarm there, a worker
 * afterwards, often in a process that did not exist a second earlier — and none of it used to leave
 * a trace. Working out why a real drive produced no pin meant reading the Bluetooth stack's own
 * dump and the app's DataStore file off the device, which is not a thing anyone can do from the
 * driver's seat.
 *
 * ## What must never go in here
 *
 * **Coordinates, addresses, curb ids, or anything else that says where the car is.** This app's
 * premise is that not even its own server learns that, and a logcat line outlives the process, goes
 * into bug reports, and is read by whoever is handed the phone. Distances, accuracies, durations,
 * fix qualities and counts answer every question a detection bug asks, and none of them is a
 * location. The same goes for a stereo's MAC address, which identifies one specific car — the name
 * the user gave it debugs just as well, and the address is [d]-only.
 */
object CurbsideLog {

    const val TAG = "Curbside"

    /** The detection chain. A handful of lines per drive, and nothing at all on a day without one. */
    fun i(message: String) {
        Log.i(TAG, message)
    }

    /** Something that should have worked did not. Always shipped: these are the ones to see. */
    fun w(message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }

    /** Detail worth having while testing in a car and not worth shipping. Debug builds only. */
    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
