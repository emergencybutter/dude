package nyc.curbside.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nyc.curbside.drive.DrivePhase
import nyc.curbside.drive.DriveState
import nyc.curbside.drive.SignalSource

private val Context.dataStore by preferencesDataStore(name = "curbside")

private val vehicleJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Everything small enough not to deserve a table.
 *
 * The drive state lives here rather than in Room on purpose: it is read and written from broadcast
 * receivers in a process that may have been started three milliseconds ago, and DataStore's single
 * small file beats opening a database for two fields.
 */
@Singleton
class CurbsideSettings @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private object Keys {
        val DRIVE_PHASE = stringPreferencesKey("drive_phase")
        val DRIVE_STARTED_AT = longPreferencesKey("drive_started_at")
        val DRIVE_STARTED_BY = stringPreferencesKey("drive_started_by")
        val DRIVE_CONFIRM_AT = longPreferencesKey("drive_confirm_at")
        val DRIVE_ENDED_BY = stringPreferencesKey("drive_ended_by")

        val CAR_BLUETOOTH_ADDRESS = stringPreferencesKey("car_bluetooth_address")
        val CAR_BLUETOOTH_NAME = stringPreferencesKey("car_bluetooth_name")

        val VEHICLES = stringPreferencesKey("vehicles")

        /** The stereo seen during the drive now in progress, if one was. */
        val DRIVE_STEREO = stringPreferencesKey("drive_stereo")

        /** Where the drive now in progress began. Free: whatever fix the phone already had. */
        val DRIVE_ORIGIN_LAT = stringPreferencesKey("drive_origin_lat")
        val DRIVE_ORIGIN_LON = stringPreferencesKey("drive_origin_lon")
        val DRIVE_ORIGIN_AT = longPreferencesKey("drive_origin_at")

        val BREADCRUMB_LAT = stringPreferencesKey("breadcrumb_lat")
        val BREADCRUMB_LON = stringPreferencesKey("breadcrumb_lon")
        val BREADCRUMB_AT = longPreferencesKey("breadcrumb_at")
        val BREADCRUMB_ACCURACY = stringPreferencesKey("breadcrumb_accuracy")

        val AUTO_SHARE = booleanPreferencesKey("auto_share")
        val HOUSEHOLD_ID = stringPreferencesKey("household_id")

        val REMIND_MINUTES_BEFORE = longPreferencesKey("remind_minutes_before")
        val ASP_DATASET_VERSION = stringPreferencesKey("asp_dataset_version")
        val SUSPENSIONS_JSON = stringPreferencesKey("suspensions_json")
        val TRANSITIONS_REGISTERED = booleanPreferencesKey("transitions_registered")
        val PERMISSIONS_EXPLAINED = booleanPreferencesKey("permissions_explained")
    }

    val driveState: Flow<DriveState> = context.dataStore.data.map { it.toDriveState() }

    suspend fun readDriveState(): DriveState = context.dataStore.data.first().toDriveState()

    suspend fun writeDriveState(state: DriveState) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DRIVE_PHASE] = state.phase.name
            state.driveStartedAt?.let { prefs[Keys.DRIVE_STARTED_AT] = it.toEpochMilli() }
                ?: prefs.remove(Keys.DRIVE_STARTED_AT)
            state.startedBy?.let { prefs[Keys.DRIVE_STARTED_BY] = it.name }
                ?: prefs.remove(Keys.DRIVE_STARTED_BY)
            state.confirmAt?.let { prefs[Keys.DRIVE_CONFIRM_AT] = it.toEpochMilli() }
                ?: prefs.remove(Keys.DRIVE_CONFIRM_AT)
            state.endedBy?.let { prefs[Keys.DRIVE_ENDED_BY] = it.name }
                ?: prefs.remove(Keys.DRIVE_ENDED_BY)
        }
    }

    private fun Preferences.toDriveState() = DriveState(
        phase = this[Keys.DRIVE_PHASE]?.let { runCatching { DrivePhase.valueOf(it) }.getOrNull() }
            ?: DrivePhase.IDLE,
        driveStartedAt = this[Keys.DRIVE_STARTED_AT]?.let(Instant::ofEpochMilli),
        startedBy = this[Keys.DRIVE_STARTED_BY]?.let { runCatching { SignalSource.valueOf(it) }.getOrNull() },
        confirmAt = this[Keys.DRIVE_CONFIRM_AT]?.let(Instant::ofEpochMilli),
        endedBy = this[Keys.DRIVE_ENDED_BY]?.let { runCatching { SignalSource.valueOf(it) }.getOrNull() },
    )

    /** The stereo the user nominated as "my car". Null until they pick one in Settings. */
    val carBluetoothAddress: Flow<String?> = context.dataStore.data.map { it[Keys.CAR_BLUETOOTH_ADDRESS] }
    val carBluetoothName: Flow<String?> = context.dataStore.data.map { it[Keys.CAR_BLUETOOTH_NAME] }

    suspend fun setCarBluetooth(address: String?, name: String?) {
        context.dataStore.edit { prefs ->
            address?.let { prefs[Keys.CAR_BLUETOOTH_ADDRESS] = it } ?: prefs.remove(Keys.CAR_BLUETOOTH_ADDRESS)
            name?.let { prefs[Keys.CAR_BLUETOOTH_NAME] = it } ?: prefs.remove(Keys.CAR_BLUETOOTH_NAME)
        }
    }

    suspend fun readCarBluetoothAddress(): String? = context.dataStore.data.first()[Keys.CAR_BLUETOOTH_ADDRESS]

    // ------------------------------------------------------------------------
    // Vehicles
    // ------------------------------------------------------------------------

    /**
     * The household's cars.
     *
     * Reads through the single nominated stereo that came before it, so an existing install keeps
     * working and arrives with its one car already set up rather than an empty list and a detector
     * that has quietly stopped recognising anything.
     */
    val vehicles: Flow<List<Vehicle>> = context.dataStore.data.map { it.toVehicles() }

    suspend fun readVehicles(): List<Vehicle> = context.dataStore.data.first().toVehicles()

    suspend fun setVehicles(vehicles: List<Vehicle>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VEHICLES] = vehicleJson.encodeToString(vehicles)
            // The old single-car setting is what the detector used to read. Keep it pointing at a
            // real stereo so a downgrade, or any code path still asking, is not left with a stale
            // address for a car that has been removed.
            val first = vehicles.firstOrNull { it.bluetoothAddress != null }
            first?.bluetoothAddress?.let { prefs[Keys.CAR_BLUETOOTH_ADDRESS] = it }
                ?: prefs.remove(Keys.CAR_BLUETOOTH_ADDRESS)
            first?.name?.let { prefs[Keys.CAR_BLUETOOTH_NAME] = it } ?: prefs.remove(Keys.CAR_BLUETOOTH_NAME)
        }
    }

    private fun Preferences.toVehicles(): List<Vehicle> {
        this[Keys.VEHICLES]?.let { stored ->
            runCatching { vehicleJson.decodeFromString<List<Vehicle>>(stored) }
                .getOrNull()
                ?.let { return it }
        }
        // Nothing stored: carry the pre-vehicles setting forward rather than starting empty.
        val address = this[Keys.CAR_BLUETOOTH_ADDRESS] ?: return emptyList()
        return listOf(Vehicle.fromStereo(address, this[Keys.CAR_BLUETOOTH_NAME] ?: "My car"))
    }

    // ------------------------------------------------------------------------
    // The drive in progress
    // ------------------------------------------------------------------------

    /** Remembers which stereo was seen, so the capture can name the car it belonged to. */
    suspend fun setDriveStereo(address: String?) {
        context.dataStore.edit { prefs ->
            address?.let { prefs[Keys.DRIVE_STEREO] = it } ?: prefs.remove(Keys.DRIVE_STEREO)
        }
    }

    suspend fun readDriveStereo(): String? = context.dataStore.data.first()[Keys.DRIVE_STEREO]

    /**
     * Where this drive started, used to tell one car from another when no stereo did.
     *
     * Deliberately whatever the phone had cached — see [nyc.curbside.location.LocationFixer]. No
     * fix is turned on to answer this; an absent or stale origin simply means the question gets
     * put to the user instead.
     */
    suspend fun setDriveOrigin(origin: Breadcrumb?) {
        context.dataStore.edit { prefs ->
            if (origin == null) {
                prefs.remove(Keys.DRIVE_ORIGIN_LAT)
                prefs.remove(Keys.DRIVE_ORIGIN_LON)
                prefs.remove(Keys.DRIVE_ORIGIN_AT)
            } else {
                prefs[Keys.DRIVE_ORIGIN_LAT] = origin.latitude.toString()
                prefs[Keys.DRIVE_ORIGIN_LON] = origin.longitude.toString()
                prefs[Keys.DRIVE_ORIGIN_AT] = origin.at.toEpochMilli()
            }
        }
    }

    suspend fun readDriveOrigin(): Breadcrumb? {
        val prefs = context.dataStore.data.first()
        val lat = prefs[Keys.DRIVE_ORIGIN_LAT]?.toDoubleOrNull() ?: return null
        val lon = prefs[Keys.DRIVE_ORIGIN_LON]?.toDoubleOrNull() ?: return null
        val at = prefs[Keys.DRIVE_ORIGIN_AT] ?: return null
        return Breadcrumb(lat, lon, Float.MAX_VALUE, java.time.Instant.ofEpochMilli(at))
    }

    /**
     * The most recent free location fix seen while driving. Costs nothing to collect (see
     * [nyc.curbside.location.PassiveBreadcrumb]) and is the only thing that saves the capture when
     * the car ends up in an underground garage.
     */
    val breadcrumb: Flow<Breadcrumb?> = context.dataStore.data.map { it.toBreadcrumb() }

    suspend fun readBreadcrumb(): Breadcrumb? = context.dataStore.data.first().toBreadcrumb()

    suspend fun writeBreadcrumb(crumb: Breadcrumb) {
        context.dataStore.edit { prefs ->
            // Doubles are stored as strings because DataStore Preferences has no double key type
            // and rounding a coordinate to a float loses about a metre.
            prefs[Keys.BREADCRUMB_LAT] = crumb.latitude.toString()
            prefs[Keys.BREADCRUMB_LON] = crumb.longitude.toString()
            prefs[Keys.BREADCRUMB_AT] = crumb.at.toEpochMilli()
            prefs[Keys.BREADCRUMB_ACCURACY] = crumb.accuracyMeters.toString()
        }
    }

    private fun Preferences.toBreadcrumb(): Breadcrumb? {
        val lat = this[Keys.BREADCRUMB_LAT]?.toDoubleOrNull() ?: return null
        val lon = this[Keys.BREADCRUMB_LON]?.toDoubleOrNull() ?: return null
        val at = this[Keys.BREADCRUMB_AT] ?: return null
        return Breadcrumb(
            latitude = lat,
            longitude = lon,
            accuracyMeters = this[Keys.BREADCRUMB_ACCURACY]?.toFloatOrNull() ?: Float.MAX_VALUE,
            at = Instant.ofEpochMilli(at),
        )
    }

    val autoShareEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SHARE] ?: false }
    suspend fun readAutoShareEnabled(): Boolean = context.dataStore.data.first()[Keys.AUTO_SHARE] ?: false
    suspend fun setAutoShare(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SHARE] = enabled }
    }

    val householdId: Flow<String?> = context.dataStore.data.map { it[Keys.HOUSEHOLD_ID] }
    suspend fun readHouseholdId(): String? = context.dataStore.data.first()[Keys.HOUSEHOLD_ID]
    suspend fun setHouseholdId(id: String?) {
        context.dataStore.edit { prefs ->
            id?.let { prefs[Keys.HOUSEHOLD_ID] = it } ?: prefs.remove(Keys.HOUSEHOLD_ID)
        }
    }

    /** How far ahead of the sweeping to warn. Defaults to an hour: enough time to find a new spot. */
    val remindMinutesBefore: Flow<Long> = context.dataStore.data.map { it[Keys.REMIND_MINUTES_BEFORE] ?: 60L }
    suspend fun readRemindMinutesBefore(): Long = context.dataStore.data.first()[Keys.REMIND_MINUTES_BEFORE] ?: 60L
    suspend fun setRemindMinutesBefore(minutes: Long) {
        context.dataStore.edit { it[Keys.REMIND_MINUTES_BEFORE] = minutes }
    }

    val aspDatasetVersion: Flow<String?> = context.dataStore.data.map { it[Keys.ASP_DATASET_VERSION] }
    suspend fun readAspDatasetVersion(): String? = context.dataStore.data.first()[Keys.ASP_DATASET_VERSION]
    suspend fun setAspDatasetVersion(version: String) {
        context.dataStore.edit { it[Keys.ASP_DATASET_VERSION] = version }
    }

    val suspensionsJson: Flow<String?> = context.dataStore.data.map { it[Keys.SUSPENSIONS_JSON] }
    suspend fun readSuspensionsJson(): String? = context.dataStore.data.first()[Keys.SUSPENSIONS_JSON]
    suspend fun setSuspensionsJson(json: String) {
        context.dataStore.edit { it[Keys.SUSPENSIONS_JSON] = json }
    }

    suspend fun readTransitionsRegistered(): Boolean = context.dataStore.data.first()[Keys.TRANSITIONS_REGISTERED] ?: false
    suspend fun setTransitionsRegistered(registered: Boolean) {
        context.dataStore.edit { it[Keys.TRANSITIONS_REGISTERED] = registered }
    }

    /**
     * Whether the user has been through the permissions explanation once.
     *
     * Deliberately not "has every permission": someone who read the screen and decided Curbside may
     * not have their location all the time has answered the question, and asking again on every
     * launch would be nagging. Settings keeps a way back in.
     */
    val permissionsExplained: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.PERMISSIONS_EXPLAINED] ?: false }

    suspend fun setPermissionsExplained(explained: Boolean) {
        context.dataStore.edit { it[Keys.PERMISSIONS_EXPLAINED] = explained }
    }
}

data class Breadcrumb(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val at: Instant,
)
