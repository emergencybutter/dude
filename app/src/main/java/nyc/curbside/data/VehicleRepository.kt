package nyc.curbside.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import nyc.curbside.asp.Geo
import nyc.curbside.asp.LatLng
import nyc.curbside.data.db.ParkingEventDao
import nyc.curbside.drive.VehicleCandidate
import nyc.curbside.drive.VehicleIdentification
import nyc.curbside.drive.VehicleIdentifier

/**
 * The household's cars, and which of them a drive was in.
 *
 * The decision itself lives in [VehicleIdentifier], which knows nothing about Android or this
 * database; everything here is the fetching that feeds it. What it feeds it is deliberately small:
 * the stereo seen during the drive, and how far each car was last left from where the drive began.
 */
@Singleton
class VehicleRepository @Inject constructor(
    private val settings: CurbsideSettings,
    private val dao: ParkingEventDao,
) {

    val vehicles: Flow<List<Vehicle>> = settings.vehicles

    suspend fun all(): List<Vehicle> = settings.readVehicles()

    suspend fun byId(id: String?): Vehicle? = id?.let { all().firstOrNull { car -> car.id == it } }

    suspend fun add(vehicle: Vehicle) {
        val existing = all().filterNot { it.id == vehicle.id }
        settings.setVehicles(existing + vehicle)
    }

    suspend fun remove(id: String) {
        settings.setVehicles(all().filterNot { it.id == id })
    }

    suspend fun rename(id: String, name: String) {
        settings.setVehicles(all().map { if (it.id == id) it.copy(name = name) else it })
    }

    /**
     * Which car the drive that just ended was in.
     *
     * Returns [VehicleIdentification.Ask] when no car is set up at all, which is the ordinary state
     * for someone who has never opened the vehicles screen. Nothing downstream treats that as an
     * error — the parking is simply recorded without a car, exactly as it was before this existed.
     */
    suspend fun identifyDrive(): VehicleIdentification = withContext(Dispatchers.IO) {
        val vehicles = settings.readVehicles()
        if (vehicles.isEmpty()) return@withContext VehicleIdentification.Ask()

        val stereo = settings.readDriveStereo()
        val origin = settings.readDriveOrigin()?.let { LatLng(it.latitude, it.longitude) }
        val lastSeen = lastKnownPositions()

        VehicleIdentifier.identify(
            stereoAddress = stereo,
            candidates = vehicles.map { vehicle ->
                VehicleCandidate(
                    id = vehicle.id,
                    bluetoothAddress = vehicle.bluetoothAddress,
                    metersFromDriveOrigin = origin?.let { from ->
                        lastSeen[vehicle.id]?.let { Geo.haversineMeters(from, it) }
                    },
                )
            },
        )
    }

    /** Where each car is currently parked, as far as any phone in the household knows. */
    suspend fun lastKnownPositions(): Map<String, LatLng> = withContext(Dispatchers.IO) {
        dao.active()
            .filter { it.vehicleId != null }
            .groupBy { it.vehicleId!! }
            // active() is newest first and groupBy keeps that order, so the head of each group is
            // where that car is now. associateBy would have kept the oldest instead.
            .mapValues { (_, events) -> events.first().let { LatLng(it.latitude, it.longitude) } }
    }

    /** Forgets the drive that just ended, once its car has been decided. */
    suspend fun forgetDrive() {
        settings.setDriveStereo(null)
        settings.setDriveOrigin(null)
    }
}
