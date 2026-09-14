/*
 * LLD: Uber / Ride Sharing System
 *
 * ============================================================
 * REQUIREMENTS
 * ============================================================
 *
 * Functional:
 * 1. Register riders and drivers.
 * 2. Driver can go AVAILABLE / OFFLINE.
 * 3. Rider requests a ride with pickup and destination.
 * 4. Assign the nearest available driver.
 * 5. Start, complete, or cancel a ride.
 * 6. Both rider and driver can cancel an active ride.
 * 7. Calculate fare based on pickup -> destination distance.
 * 8. Driver becomes AVAILABLE after completion/cancellation.
 *
 * Out of scope:
 * - Payments
 * - Notifications
 * - Real-time GPS / Maps APIs
 * - Database / persistence
 * - Authentication
 * - Ratings
 * - Surge pricing
 * - Different ride types
 *
 *
 * ============================================================
 * CORE ENTITIES
 * ============================================================
 *
 * Location
 * Rider
 * Driver
 * Ride
 *
 * Service:
 * RideService
 * FareService
 *
 *
 * ============================================================
 * KEY DESIGN DECISIONS
 * ============================================================
 *
 * 1. Ride owns its state transitions.
 *    Ride decides whether start/complete/cancel is valid.
 *
 * 2. RideService coordinates the overall workflow.
 *    It handles driver assignment, authorization and driver state.
 *
 * 3. FareService is an interface.
 *    Makes fare calculation replaceable without changing RideService.
 *
 * 4. Nearest driver:
 *    Simple O(D) scan over all drivers.
 *    Production: use geospatial indexing / H3 / Geohash.
 *
 * 5. Driver is reserved immediately when assigned.
 *    AVAILABLE -> ON_RIDE
 *    We don't introduce RESERVED / ACCEPT flow unless required.
 *
 * 6. Thread safety:
 *    requestRide is synchronized so two concurrent riders cannot
 *    assign the same available driver.
 *
 *    Ride lifecycle operations are also synchronized.
 *
 * 7. HashMap is sufficient for this in-memory interview solution.
 *    Production would need DB + distributed locking/coordination.
 *
 * 8. Distance calculation below is simplified Euclidean distance.
 *    Production would use Haversine / Maps / geospatial service.
 */


package org.lld;

import java.util.*;


class Location {

    final double lat;
    final double lng;

    Location(double lat, double lng) {
        this.lat = lat;
        this.lng = lng;
    }

    /*
     * Simplified distance calculation.
     *
     * This is NOT actual distance in km.
     * In production, use Haversine formula or a Maps service.
     */
    double distanceTo(Location other) {
        double latDiff = lat - other.lat;
        double lngDiff = lng - other.lng;

        return Math.sqrt(
                latDiff * latDiff +
                        lngDiff * lngDiff
        );
    }
}



class Rider {

    final String id;
    final String name;

    Rider(String id, String name) {
        this.id = id;
        this.name = name;
    }
}



enum DriverStatus {
    AVAILABLE,
    OFFLINE,
    ON_RIDE
}


class Driver {

    final String id;
    final String name;

    Location location;
    DriverStatus status;

    Driver(String id, String name) {
        this.id = id;
        this.name = name;

        /*
         * New drivers start OFFLINE.
         * They must explicitly become AVAILABLE.
         */
        this.status = DriverStatus.OFFLINE;
    }

    void setStatus(DriverStatus status) {
        this.status = status;
    }

    void setLocation(Location location) {
        this.location = location;
    }
}


enum RideStatus {
    DRIVER_ASSIGNED,
    STARTED,
    COMPLETED,
    CANCELED
}


class Ride {

    final String id;
    final Rider rider;
    final Driver driver;

    final Location pickup;
    final Location destination;

    RideStatus status;

    double fare;

    Ride(
            Rider rider,
            Driver driver,
            Location pickup,
            Location destination
    ) {
        this.id = UUID.randomUUID().toString();

        this.rider = rider;
        this.driver = driver;

        this.pickup = pickup;
        this.destination = destination;

        this.status = RideStatus.DRIVER_ASSIGNED;
    }

    void start() {

        if (status != RideStatus.DRIVER_ASSIGNED) {
            throw new IllegalStateException(
                    "Ride cannot be started from current state"
            );
        }

        status = RideStatus.STARTED;
    }

    void complete() {

        if (status != RideStatus.STARTED) {
            throw new IllegalStateException(
                    "Ride cannot be completed before it starts"
            );
        }

        status = RideStatus.COMPLETED;
    }

    /*
     * DRIVER_ASSIGNED -> CANCELED
     * STARTED         -> CANCELED
     *
     * Both rider and driver are allowed to cancel.
     */
    void cancel() {

        if (status != RideStatus.DRIVER_ASSIGNED &&
                status != RideStatus.STARTED) {

            throw new IllegalStateException(
                    "Ride cannot be canceled from current state"
            );
        }

        status = RideStatus.CANCELED;
    }
}


// ============================================================
// FARE SERVICE
// ============================================================

/*
 * Strategy/abstraction for fare calculation.
 *
 * If later we add:
 * - Surge pricing
 * - Premium rides
 * - Different pricing models
 *
 * RideService does not need to change.
 */
interface FareService {

    double calculateFare(double distance);
}


class SimpleFareService implements FareService {

    private final double basePrice;
    private final double pricePerKm;

    SimpleFareService(
            double basePrice,
            double pricePerKm
    ) {
        this.basePrice = basePrice;
        this.pricePerKm = pricePerKm;
    }

    @Override
    public double calculateFare(double distance) {

        return basePrice + distance * pricePerKm;
    }
}



class RideService {

    /*
     * Simple in-memory storage.
     *
     * Production:
     * - Database / repositories
     * - Distributed state
     */
    private final Map<String, Rider> riders;
    private final Map<String, Driver> drivers;
    private final Map<String, Ride> rides;

    private final FareService fareService;


    RideService(FareService fareService) {

        this.fareService = fareService;

        this.riders = new HashMap<>();
        this.drivers = new HashMap<>();
        this.rides = new HashMap<>();
    }


    void registerRider(String id, String name) {

        if (riders.containsKey(id)) {
            throw new IllegalArgumentException(
                    "Rider already exists"
            );
        }

        riders.put(id, new Rider(id, name));
    }

    void registerDriver(String id, String name) {

        if (drivers.containsKey(id)) {
            throw new IllegalArgumentException(
                    "Driver already exists"
            );
        }

        drivers.put(id, new Driver(id, name));
    }


    // --------------------------------------------------------
    // REQUEST RIDE
    // --------------------------------------------------------

    /*
     * synchronized is important here.
     *
     * Example:
     *
     * Rider A requests
     * Rider B requests
     *
     * Both may see the same driver as AVAILABLE.
     *
     * Synchronization makes assignment atomic:
     *
     * find driver
     *     +
     * mark driver ON_RIDE
     *     +
     * create ride
     *
     * happen as one critical section.
     */
    synchronized Ride requestRide(
            Location pickup,
            Location destination,
            String riderId
    ) {

        if (pickup == null || destination == null) {
            throw new IllegalArgumentException(
                    "Pickup and destination are required"
            );
        }

        Rider rider = riders.get(riderId);

        if (rider == null) {
            throw new IllegalArgumentException(
                    "Invalid rider"
            );
        }

        Driver driver = findNearestDriver(pickup);

        if (driver == null) {
            throw new IllegalStateException(
                    "No available drivers nearby"
            );
        }

        /*
         * Reserve driver immediately.
         *
         * We don't have a separate RESERVED state because
         * there is no driver accept/reject flow in this scope.
         */
        driver.setStatus(DriverStatus.ON_RIDE);

        Ride ride = new Ride(
                rider,
                driver,
                pickup,
                destination
        );

        rides.put(ride.id, ride);

        return ride;
    }


    // --------------------------------------------------------
    // FIND NEAREST DRIVER
    // --------------------------------------------------------

    /*
     * Simple O(D) solution.
     *
     * For every driver:
     * - Must be AVAILABLE
     * - Must have a location
     * - Find minimum distance from pickup
     *
     * Production:
     * O(D) scan does not scale.
     * Use H3 / Geohash / spatial index.
     */
    private Driver findNearestDriver(Location pickup) {

        Driver nearestDriver = null;

        /*
         * Double.MAX_VALUE is safer than Integer.MAX_VALUE
         * because distance is a double.
         */
        double minDistance = Double.MAX_VALUE;

        for (Driver driver : drivers.values()) {

            if (driver.status != DriverStatus.AVAILABLE) {
                continue;
            }

            if (driver.location == null) {
                continue;
            }

            double distance =
                    driver.location.distanceTo(pickup);

            if (distance < minDistance) {
                minDistance = distance;
                nearestDriver = driver;
            }
        }

        return nearestDriver;
    }


    // --------------------------------------------------------
    // START RIDE
    // --------------------------------------------------------

    synchronized void startRide(
            String rideId,
            String driverId
    ) {

        Ride ride = getRide(rideId);

        if (!ride.driver.id.equals(driverId)) {
            throw new IllegalArgumentException(
                    "Driver is not authorized"
            );
        }
        ride.start();
    }


    // --------------------------------------------------------
    // CANCEL RIDE
    // --------------------------------------------------------

    synchronized void cancelRide(
            String rideId,
            String userId
    ) {

        Ride ride = getRide(rideId);

        /*
         * Both rider and assigned driver can cancel.
         */
        if (!ride.rider.id.equals(userId) &&
                !ride.driver.id.equals(userId)) {

            throw new IllegalArgumentException(
                    "User is not authorized"
            );
        }
        ride.cancel();
        ride.driver.setStatus(DriverStatus.AVAILABLE);
    }


    // --------------------------------------------------------
    // COMPLETE RIDE
    // --------------------------------------------------------

    synchronized void completeRide(
            String rideId,
            String driverId
    ) {

        Ride ride = getRide(rideId);

        /*
         * Only the assigned driver can complete the ride.
         */
        if (!ride.driver.id.equals(driverId)) {
            throw new IllegalArgumentException(
                    "Driver is not authorized"
            );
        }

        /*
         * Ride validates STARTED -> COMPLETED.
         */
        ride.complete();

        /*
         * Fare is based on actual ride distance:
         *
         * pickup -> destination
         *
         * NOT:
         * driver -> pickup
         */
        double distance =
                ride.pickup.distanceTo(ride.destination);

        ride.fare = fareService.calculateFare(distance);

        /*
         * Driver becomes available for another ride.
         */
        ride.driver.setStatus(DriverStatus.AVAILABLE);
    }


    // --------------------------------------------------------
    // UPDATE DRIVER LOCATION
    // --------------------------------------------------------

    void updateDriverLocation(
            String driverId,
            Location location
    ) {

        if (location == null) {
            throw new IllegalArgumentException(
                    "Location is required"
            );
        }

        Driver driver = drivers.get(driverId);

        if (driver == null) {
            throw new IllegalArgumentException(
                    "Invalid driver"
            );
        }

        driver.setLocation(location);
    }


    // --------------------------------------------------------
    // UPDATE DRIVER STATUS
    // --------------------------------------------------------

    void updateDriverStatus(
            String driverId,
            DriverStatus status
    ) {

        Driver driver = drivers.get(driverId);

        if (driver == null) {
            throw new IllegalArgumentException(
                    "Invalid driver"
            );
        }

        /*
         * Driver cannot manually set ON_RIDE.
         *
         * RideService changes it internally during assignment.
         */
        if (status == DriverStatus.ON_RIDE) {
            throw new IllegalArgumentException(
                    "Driver cannot manually set ON_RIDE"
            );
        }

        driver.setStatus(status);
    }


    // --------------------------------------------------------
    // GET RIDE
    // --------------------------------------------------------

    private Ride getRide(String rideId) {

        Ride ride = rides.get(rideId);

        if (ride == null) {
            throw new IllegalArgumentException(
                    "Invalid ride"
            );
        }

        return ride;
    }
}


// ============================================================
// DEMO
// ============================================================

public class Uber {

    public static void main(String[] args) {

        RideService uber =
                new RideService(
                        new SimpleFareService(
                                10.0,   // base fare
                                3.0     // price per distance unit
                        )
                );


        // ----------------------------------------------------
        // REGISTER USERS
        // ----------------------------------------------------

        uber.registerRider("1", "Ebin");
        uber.registerRider("2", "Vishnu");

        uber.registerDriver("10", "Shakir");
        uber.registerDriver("11", "MK");


        // ----------------------------------------------------
        // UPDATE DRIVER LOCATIONS
        // ----------------------------------------------------

        uber.updateDriverLocation(
                "10",
                new Location(34.0, 6.0)
        );

        uber.updateDriverLocation(
                "11",
                new Location(32.0, 4.0)
        );


        // ----------------------------------------------------
        // MAKE DRIVERS AVAILABLE
        // ----------------------------------------------------

        uber.updateDriverStatus(
                "10",
                DriverStatus.AVAILABLE
        );

        uber.updateDriverStatus(
                "11",
                DriverStatus.AVAILABLE
        );


        // ----------------------------------------------------
        // REQUEST RIDE
        // ----------------------------------------------------

        Ride ride = uber.requestRide(
                new Location(10.0, 2.4),
                new Location(20.0, 4.0),
                "1"
        );

        System.out.println(
                "Driver: " + ride.driver.name
        );

        System.out.println(
                "Status: " + ride.status
        );


        // ----------------------------------------------------
        // START RIDE
        // ----------------------------------------------------

        uber.startRide(
                ride.id,
                ride.driver.id
        );

        System.out.println(
                "Status: " + ride.status
        );


        // ----------------------------------------------------
        // COMPLETE RIDE
        // ----------------------------------------------------

        uber.completeRide(
                ride.id,
                ride.driver.id
        );

        System.out.println(
                "Status: " + ride.status
        );

        System.out.println(
                "Fare: " + ride.fare
        );
    }
}


/*
 * ============================================================
 * QUICK REVISION NOTES
 * ============================================================
 *
 * Entities:
 * Rider, Driver, Ride, Location
 *
 * Service:
 * RideService
 *
 * Extensibility:
 * FareService -> Strategy for fare calculation.
 *
 * Ride state:
 *
 * DRIVER_ASSIGNED -> STARTED -> COMPLETED
 *          |
 *          v
 *       CANCELED
 *
 * STARTED can also -> CANCELED.
 *
 *
 * Driver state:
 *
 * OFFLINE -> AVAILABLE -> ON_RIDE -> AVAILABLE
 *
 *
 * Concurrency:
 * requestRide is synchronized so one driver cannot be
 * assigned to two rides concurrently.
 *
 *
 * Nearest driver:
 * O(D) scan.
 * Production -> spatial index / H3 / Geohash.
 *
 *
 * Important interview point:
 * Don't overengineer this.
 *
 * No Repository
 * No Factory
 * No RideManager
 * No DriverManager
 * No LocationService
 *
 * Add those only if requirements force them.
 *
 *
 * Production discussion if interviewer asks:
 *
 * - DB / persistence
 * - Redis for active driver state
 * - H3/Geohash for nearby-driver lookup
 * - Distributed locking / atomic driver reservation
 * - Kafka/events for ride lifecycle
 * - Maps service for routing/distance
 * - Payment service
 * - Notification service
 */