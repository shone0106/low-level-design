/*
Functional requirements:
- support multiple elevators in a building with N floors
- Handle two request types:
    1. External request(hall): a person on a floor presses up/down to call an elevator
    2. Internal request(car): a person inside presses the button for their destination floor
- dispatch the most suitable elevator to serve a hall request
- move elevator and serve requests in an efficient manner (LOOK algorithm)
- Track state of each elevator (current floor, status - moving up/down/idle)

Core entities:
- Elevator: id, currentFloor, status, requestedFloors, strategy
- ElevatorMovingStrategy: how an elevator moves/serves floors (LOOK)
- ElevatorDispatcher: dispatches an elevator to serve a hall request
- ElevatorFactory: single place to configure/construct elevators
- ElevatorSystem: façade exposing hall/car requests + stepping

Key design decisions:
- Strategy pattern for movement (OCP: swap LOOK/SCAN without touching Elevator)
- Dispatcher interface (OCP: swap dispatch policy independently)
- Factory owns strategy wiring -> strategy is final, no null window
- Concurrency intentionally out of scope for now
*/

package org.example;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, Elevator!");

        ElevatorFactory factory = new ElevatorFactory(new LookStrategy());

        Map<String, Elevator> elevators = Map.of(
                "E1", factory.create("E1"),
                "E2", factory.create("E2"),
                "E3", factory.create("E3")
        );

        ElevatorSystem system = new ElevatorSystem(elevators,
                new SimpleElevatorDispatcher(), 0, 10);

        system.hallRequest(3, Direction.UP);
        system.hallRequest(5, Direction.DOWN);
        system.carRequest("E1", 7);
        system.carRequest("E2", 8);
        system.hallRequest(7, Direction.DOWN);

        for (int i = 0; i < 20; i++) {
            system.tick();
        }
    }
}


enum ElevatorStatus {
    MOVING_UP,
    MOVING_DOWN,
    IDLE
}

enum Direction {
    UP,
    DOWN
}


interface ElevatorMovingStrategy {
    void onFloorAdded(Elevator elevator); // decide direction when idle
    void step(Elevator elevator);         // advance one step, serve requests if stopping
}

// LOOK: keep going in current direction until no requests ahead, then reverse.
class LookStrategy implements ElevatorMovingStrategy {

    @Override
    public void onFloorAdded(Elevator e) {
        if (e.status == ElevatorStatus.IDLE && !e.requestedFloors.isEmpty()) {
            e.status = (e.requestedFloors.last() > e.currentFloor)
                    ? ElevatorStatus.MOVING_UP
                    : ElevatorStatus.MOVING_DOWN;
        }
    }

    @Override
    public void step(Elevator e) {
        if (e.status == ElevatorStatus.IDLE || e.requestedFloors.isEmpty()) {
            e.status = ElevatorStatus.IDLE;
            return;
        }

        if (e.status == ElevatorStatus.MOVING_UP) {
            Integer nextFloor = e.requestedFloors.ceiling(e.currentFloor + 1);
            if (nextFloor != null) {
                e.currentFloor++;
                System.out.println("Elevator " + e.id + " moving up to floor " + e.currentFloor);
                if (e.requestedFloors.remove(e.currentFloor)) {
                    System.out.println("Elevator " + e.id + " stopping at floor " + e.currentFloor);
                }
            } else {
                e.status = ElevatorStatus.MOVING_DOWN; // reverse
            }
        } else { // MOVING_DOWN
            Integer nextFloor = e.requestedFloors.floor(e.currentFloor - 1);
            if (nextFloor != null) {
                e.currentFloor--;
                System.out.println("Elevator " + e.id + " moving down to floor " + e.currentFloor);
                if (e.requestedFloors.remove(e.currentFloor)) {
                    System.out.println("Elevator " + e.id + " stopping at floor " + e.currentFloor);
                }
            } else {
                e.status = ElevatorStatus.MOVING_UP; // reverse
            }
        }

        if (e.requestedFloors.isEmpty()) {
            e.status = ElevatorStatus.IDLE;
        }
    }
}


class Elevator {
    final String id;
    int currentFloor;
    ElevatorStatus status;
    TreeSet<Integer> requestedFloors; // sorted set enables LOOK ceiling/floor lookups
    final ElevatorMovingStrategy strategy;

    Elevator(String id, ElevatorMovingStrategy strategy) {
        this.id = id;
        this.currentFloor = 0;
        this.status = ElevatorStatus.IDLE;
        this.requestedFloors = new TreeSet<>();
        this.strategy = strategy;
    }

    void addFloor(int floor) {
        if (floor == currentFloor) {
            System.out.println("Elevator " + id + " stopping at floor " + floor);
            return;
        }
        requestedFloors.add(floor);
        strategy.onFloorAdded(this);
    }

    void step() {
        strategy.step(this);
    }
}


// Single place to construct elevators -> strategy stays final, no null window.
class ElevatorFactory {
    private final ElevatorMovingStrategy defaultStrategy;

    ElevatorFactory(ElevatorMovingStrategy defaultStrategy) {
        this.defaultStrategy = defaultStrategy;
    }

    Elevator create(String id) {
        return new Elevator(id, defaultStrategy);
    }
}


interface ElevatorDispatcher {
    void dispatch(List<Elevator> elevators, int floor, Direction direction);
}

// Direction-aware: prefers elevators already heading toward the request in the
// matching direction; penalizes those that must finish their run and come back.
class SimpleElevatorDispatcher implements ElevatorDispatcher {
    @Override
    public void dispatch(List<Elevator> elevators, int floor, Direction direction) {
        Elevator best = null;
        int bestCost = Integer.MAX_VALUE;

        for (Elevator elevator : elevators) {
            int cost = cost(elevator, floor, direction);
            if (best == null || cost < bestCost) {
                best = elevator;
                bestCost = cost;
            }
        }

        if (best != null) {
            best.addFloor(floor);
        }
    }

    // Lower cost = better fit. Penalty models the extra round-trip a car makes
    // when it cannot serve the request on its current pass (LOOK only stops for
    // requests ahead in the current direction).
    private int cost(Elevator e, int floor, Direction direction) {
        int distance = Math.abs(e.currentFloor - floor);
        int penalty = 100; // ~ building height; keeps ordering stable

        if (e.status == ElevatorStatus.IDLE) {
            return distance; // can go straight there
        }

        boolean movingUp = e.status == ElevatorStatus.MOVING_UP;
        boolean requestAbove = floor >= e.currentFloor;

        // Same direction AND request on the way -> cheapest.
        if (movingUp && direction == Direction.UP && requestAbove) {
            return distance;
        }
        if (!movingUp && direction == Direction.DOWN && !requestAbove) {
            return distance;
        }

        // Wrong direction / request behind -> overshoot, reverse, return.
        return distance + penalty;
    }
}



class ElevatorSystem {
    final Map<String, Elevator> elevators;
    final ElevatorDispatcher dispatcher;
    final int minFloor;
    final int maxFloor;

    ElevatorSystem(Map<String, Elevator> elevators, ElevatorDispatcher dispatcher,
                   int minFloor, int maxFloor) {
        this.elevators = elevators;
        this.dispatcher = dispatcher;
        this.minFloor = minFloor;
        this.maxFloor = maxFloor;
    }

    void hallRequest(int floor, Direction direction) {
        validateFloor(floor);
        dispatcher.dispatch(elevators.values().stream().toList(), floor, direction);
    }

    void carRequest(String elevatorId, int floor) {
        validateFloor(floor);
        Elevator elevator = elevators.get(elevatorId);
        if (elevator == null) {
            throw new IllegalArgumentException("Unknown elevator id: " + elevatorId);
        }
        elevator.addFloor(floor);
    }

    private void validateFloor(int floor) {
        if (floor < minFloor || floor > maxFloor) {
            throw new IllegalArgumentException(
                    "Floor " + floor + " out of range [" + minFloor + ", " + maxFloor + "]");
        }
    }

    void tick() { // represents one time tick
        for (Elevator e : elevators.values()) {
            e.step();
        }
    }
}


/*
Final notes (quick revision):

- SOLID coverage:
    SRP: state (Elevator) vs movement (Strategy) vs dispatch (Dispatcher)
    OCP: swap ElevatorMovingStrategy / ElevatorDispatcher freely
    DIP: ElevatorSystem depends on interfaces, not concretes

- Complexity: addFloor / step are O(log n) via TreeSet ceiling/floor.

Known simplifications / follow-ups to flag in an interview:
- Hall-request direction isn't stored on the elevator: LookStrategy only tracks
  destination floors, so a car may reverse before honoring the requested travel
  direction (e.g., a DOWN request at floor 5 picked up while still going UP).
  Follow-up: track the requested Direction per stop so LOOK won't reverse on a
  waiting passenger.
- Dispatch ignores load/capacity; add a load factor to the cost for realism.
- Concurrency deliberately omitted; would need per-elevator locking or a request
  queue + worker.
*/