/*
=============================================================
 INVENTORY MANAGEMENT SYSTEM - LLD REVISION NOTES
=============================================================


-------------------------------------------------------------
CLARIFYING QUESTIONS (INTERVIEW DISCUSSION SECTION)
-------------------------------------------------------------

When designing this system, clarify requirements across four themes:
1) Supported operations
2) Failure scenarios / invariants
3) Scope boundaries
4) Future extensibility

-------------------------------------------------------------
A. WAREHOUSE CONFIGURATION
-------------------------------------------------------------

Q: Are warehouses dynamic or fixed? (When you say 'multiple warehouses,' are we talking about a fixed set of warehouses configured at startup, or can warehouses be added dynamically?)

Assumption:
- Fixed set of warehouses configured at system initialization.
- We are NOT building warehouse lifecycle management.

Design Impact:
- Warehouse entity is simple.
- No create/delete warehouse APIs needed.
- Inventory logic remains the focus.

-------------------------------------------------------------
B. LOW STOCK ALERT BEHAVIOR
-------------------------------------------------------------

Q: Is low-stock threshold global per product or per warehouse? ( For the low-stock alerts you mentioned, how do those work? Do we configure a threshold per product, or is it more granular? )

Requirement:
- Threshold is per product per warehouse.

Design Match:
- lowStockThreshold is stored inside InventoryRecord.
- Since InventoryRecord represents ONE product in ONE warehouse,
  threshold naturally becomes warehouse-specific.

Q: How are notifications sent? ( How should the notification happen? Are we sending emails, calling a webhook, or just returning something to the caller? )

Requirement:
- System should trigger a callback interface.
- Delivery (email/webhook/logging) is out of scope.

Current Implementation:
- We print to console (placeholder).
- Can easily extend by injecting a LowStockAlertHandler interface.

Example extension point:
    interface LowStockAlertHandler {
        void onLowStock(productId, warehouseId, availableQty);
    }

This keeps alert triggering in scope,
but notification mechanism pluggable.

-------------------------------------------------------------
C. INVALID OPERATIONS / INVARIANTS
-------------------------------------------------------------

Q: Should inventory go negative? ( "What about invalid operations? Should we allow negative inventory, or reject operations that would take stock below zero?" )

Requirement:
- NO.
- Removing or transferring more than available stock must fail.

Design Match:
- removeStock() checks available quantity.
- reserveStock() prevents over-reservation.
- transferStock() validates before modifying.
- IllegalStateException thrown on violation.

System guarantees:
- totalQuantity >= 0
- reservedQuantity >= 0
- available >= 0

Domain enforces invariants.

-------------------------------------------------------------
D. CONCURRENCY REQUIREMENT
-------------------------------------------------------------

Q: Should system handle concurrent modifications? ( What about concurrent access? If two processes are trying to modify the same warehouse's inventory at the same time, do we need to handle that? )

Requirement:
- YES.
- Multiple threads may:
    - Add stock
    - Remove stock
    - Transfer stock
    - Reserve stock

Design Match:
- InventoryRecord methods are synchronized.
- Concurrency boundary = one InventoryRecord.
- transferStock uses ordered locking to ensure:
      - Atomic cross-record updates
      - No deadlocks
      - No partial visibility

Thread safety is built-in, not added later.

-------------------------------------------------------------
E. OUT OF SCOPE
-------------------------------------------------------------
Q. What's out of scope? Are we managing product catalogs, handling orders, that kind of thing?

Explicitly excluded:
- Product catalog management
- Order processing
- Payments
- Shipment lifecycle
- Warehouse creation/deletion
- Distributed system concerns

We focus strictly on:
Inventory tracking logic.

-------------------------------------------------------------
F. FUTURE EXTENSIONS (IF ASKED)
-------------------------------------------------------------

Possible improvements:

1) Replace console alert with pluggable callback handler.
2) Replace in-memory repository with database-backed repository.
3) Use DB row-level locking instead of synchronized.
4) Use AtomicInteger for high-contention scalability.
5) Add audit logs for inventory changes.
6) Support distributed locking for multi-node deployment.

-------------------------------------------------------------
END OF CLARIFYING QUESTIONS SECTION
-------------------------------------------------------------

-------------------------------------------------------------
1. SYSTEM OVERVIEW
-------------------------------------------------------------
We support:

1. Track inventory across multiple warehouses
2. Add stock (incoming shipment)
3. Remove stock (order fulfillment)
4. Reserve stock (prevent overselling)
5. Release reservation
6. Transfer stock between warehouses
7. Check availability
8. Low stock alerts
9. Thread-safe operations

-------------------------------------------------------------
2. CORE DESIGN STRUCTURE
-------------------------------------------------------------

Product -> simple model

Warehouse -> simple model

InventoryRecord ->
    Represents stock of ONE product in ONE warehouse.
    This is our concurrency boundary.

InventoryService ->
    Orchestrates use cases.
    Coordinates multiple InventoryRecords (e.g. transfer).

InventoryRepository ->
    Only handles persistence (get/save/query).
    NO business logic here.

Flow:
Service -> Domain (InventoryRecord) -> Repository

-------------------------------------------------------------
3. CONCURRENCY MODEL
-------------------------------------------------------------

Each InventoryRecord is thread-safe.

All mutation methods are synchronized:

    addStock()
    removeStock()
    reserveStock()
    releaseReservation()

Why?
- totalQuantity += qty is NOT atomic.
- Without synchronized, race conditions occur.
- synchronized locks on "this" object.
- Only one thread can modify that record at a time.

Good scalability:
- Different products/warehouses use different objects.
- No global locking.

-------------------------------------------------------------
4. RESERVATION MODEL
-------------------------------------------------------------

We maintain:

    totalQuantity
    reservedQuantity

Available = total - reserved

Why reservation?
- Prevents overselling.
- Order flow:
    reserve -> payment -> removeStock
- Ensures multiple threads don't sell same inventory.

-------------------------------------------------------------
5. TRANSFER LOGIC
-------------------------------------------------------------

Transfer modifies TWO InventoryRecords:
    from.removeStock()
    to.addStock()

Method-level synchronization protects single object.
But transfer must be atomic across BOTH records. (eg: during a transfer, a different thread should see the updated value when reading value of 'to')

So we use:

    synchronized(first) {
        synchronized(second) {
            ...
        }
    }

This ensures:
- Atomic cross-record operation
- No intermediate inconsistent state
- No partial visibility

-------------------------------------------------------------
6. WHY ORDERED LOCKING?
-------------------------------------------------------------

If:
Thread1 locks A then B
Thread2 locks B then A
-> DEADLOCK

Solution:
Always lock in deterministic order (e.g. warehouseId comparison).

This prevents circular wait.

-------------------------------------------------------------
7. WHY NOT PUT LOGIC IN REPOSITORY?
-------------------------------------------------------------

Repository responsibility:
- Persist & retrieve data.

Business logic belongs in:
- Domain model (InventoryRecord)
- Service layer (use case orchestration)

Putting logic in repository:
- Violates Single Responsibility Principle
- Mixes persistence with business rules
- Harder to test

-------------------------------------------------------------
8. FAQ (BASED ON COMMON DOUBTS)
-------------------------------------------------------------

Q1: If addStock/removeStock are synchronized,
    why do we need outer locking in transfer?

A:
Method-level synchronization protects ONE object.
Transfer modifies TWO objects.
We need atomicity across both.
Outer locking provides transaction-like boundary.

-------------------------------------------------------------

Q2: What happens if two threads add and remove simultaneously?

A:
Since both methods are synchronized on same object,
only one thread executes at a time.
Final value remains correct.

Without synchronized -> race condition.

-------------------------------------------------------------

Q3: Are add/remove operations really concurrent?

A:
Yes.
Multiple users/orders can modify same inventory.
Without synchronization -> corrupted state.

-------------------------------------------------------------

Q4: Can we use AtomicInteger instead?

A:
Yes.
AtomicInteger provides lock-free atomic updates.
Better scalability under high contention.
But synchronized is simpler and interview-friendly.

-------------------------------------------------------------

Q5: When is outer locking NOT required?

A:
- No transfer support
- Eventual consistency acceptable
- Using DB transactions
- Single-threaded system

-------------------------------------------------------------
9. INTERVIEW TALKING POINTS
-------------------------------------------------------------

- InventoryRecord is the concurrency boundary.
- Service orchestrates use cases.
- Repository abstracts persistence.
- Transfer uses ordered locking to avoid deadlock.
- Reservation prevents overselling.
- Can optimize with:
      AtomicInteger
      DB row-level locking
      Distributed locks (multi-node setup)

=============================================================
END OF REVISION NOTES
=============================================================
*/





package org.lld;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


//---------------------- Driver class -----------------------
public class InventoryManagementSystem {
    public static void main(String[] args) {
        System.out.println("--------Inventory management system-------");

        InventoryRepository repo = new InMemoryInventoryRepository();
        InventoryService app = new InventoryService(repo, 50);

        app.addStock("mobile", "blr", 100);
        app.addStock("mobile", "mum", 40);


        System.out.println(app.getAvailableQty("mobile", "blr"));
        app.transferStock("mobile", "mum", "blr", 30);

        System.out.println(app.getAvailableQty("mobile", "blr"));
        System.out.println(app.getAvailableQty("mobile", "mum"));

    }
}


// ------------------ DOMAIN MODEL ------------------

class Product {
    final String id;
    final String name;

    public Product(String id, String name) {
        this.id = id;
        this.name = name;
    }
}

class Warehouse {
    final String id;
    final String name;

    public Warehouse(String id, String name) {
        this.id = id;
        this.name = name;
    }
}

class InventoryRecord {
    final String productId;
    final String warehouseId;

    private int totalQuantity;
    private int reservedQuantity;
    private final int lowStockThreshold;

    public InventoryRecord(String productId, String warehouseId, int lowStockThreshold) {
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.totalQuantity = 0;
        this.reservedQuantity = 0;
        this.lowStockThreshold = lowStockThreshold;
    }

    public synchronized int getAvailableQty() {
        return totalQuantity - reservedQuantity;
    }

    public synchronized void addStock(int qty) {
        validatePositive(qty);
        totalQuantity += qty;
        checkLowStock();
    }

    public synchronized void removeStock(int qty) {
        validatePositive(qty);
        if (qty > getAvailableQty())
            throw new IllegalStateException("Insufficient available stock");
        totalQuantity -= qty;
        checkLowStock();
    }

    public synchronized void reserveStock(int qty) {
        validatePositive(qty);
        if (qty > getAvailableQty())
            throw new IllegalStateException("Insufficient available stock");
        reservedQuantity += qty;
    }

    public synchronized void releaseReservation(int qty) {
        validatePositive(qty);
        if (qty > reservedQuantity)
            throw new IllegalArgumentException("Invalid release quantity");
        reservedQuantity -= qty;
    }

    private void validatePositive(int qty) {
        if (qty <= 0)
            throw new IllegalArgumentException("Quantity must be positive");
    }

    private void checkLowStock() {
        if (getAvailableQty() <= lowStockThreshold) {
            System.out.println("Low stock alert for Product: " + productId +
                    " in Warehouse: " + warehouseId);
        }
    }
}

// ------------------ REPOSITORY ------------------

interface InventoryRepository {
    void save(InventoryRecord record);
    InventoryRecord get(String productId, String warehouseId);
    InventoryRecord getOrCreate(String productId, String warehouseId, int threshold);
    List<InventoryRecord> getByProduct(String productId);
}

class InMemoryInventoryRepository implements InventoryRepository {

    private final Map<String, InventoryRecord> store = new ConcurrentHashMap<>();

    private String key(String productId, String warehouseId) {
        return productId + "_" + warehouseId;
    }

    @Override
    public void save(InventoryRecord record) {
        store.put(key(record.productId, record.warehouseId), record);
    }

    @Override
    public InventoryRecord get(String productId, String warehouseId) {
        return store.get(key(productId, warehouseId));
    }

    @Override
    public List<InventoryRecord> getByProduct(String productId) {
        List<InventoryRecord> records = new ArrayList<>();
        for (InventoryRecord record : store.values()) {
            if (record.productId.equals(productId)) {
                records.add(record);
            }
        }
        return records;
    }
    /*
     Thread-safe "get or create" operation.
     Uses ConcurrentHashMap.computeIfAbsent() to atomically:
      - Check if record exists
      - Create and insert it if missing
     Prevents race conditions when multiple threads try to create
     the same InventoryRecord simultaneously.
    */
    @Override
    public InventoryRecord getOrCreate(String productId, String warehouseId, int threshold) {
        return store.computeIfAbsent(
                key(productId, warehouseId),
                k -> new InventoryRecord(productId, warehouseId, threshold)
        );
    }
}

// ------------------ SERVICE ------------------

class InventoryService {

    private final InventoryRepository repo;
    private final int defaultThreshold;

    public InventoryService(InventoryRepository repo, int defaultThreshold) {
        this.repo = repo;
        this.defaultThreshold = defaultThreshold;
    }

    public int getAvailableQty(String productId, String warehouseId) {
        InventoryRecord record = repo.get(productId, warehouseId);
        return record.getAvailableQty();
    }

    public void addStock(String productId, String warehouseId, int qty) {
        InventoryRecord record =
                repo.getOrCreate(productId, warehouseId, defaultThreshold);
        record.addStock(qty);
    }

    public void removeStock(String productId, String warehouseId, int qty) {
        InventoryRecord record = repo.get(productId, warehouseId);
        if (record == null)
            throw new RuntimeException("Inventory not found");
        record.removeStock(qty);
    }

    public void reserveStock(String productId, String warehouseId, int qty) {
        InventoryRecord record = repo.get(productId, warehouseId);
        if (record == null)
            throw new RuntimeException("Inventory not found");
        record.reserveStock(qty);
    }

    public void releaseReservation(String productId, String warehouseId, int qty) {
        InventoryRecord record = repo.get(productId, warehouseId);
        if (record == null)
            throw new RuntimeException("Inventory not found");
        record.releaseReservation(qty);
    }

    public void transferStock(String productId,
                              String fromWarehouseId,
                              String toWarehouseId,
                              int qty) {

        InventoryRecord from = repo.get(productId, fromWarehouseId);
        InventoryRecord to = repo.get(productId, toWarehouseId);

        if (from == null || to == null)
            throw new RuntimeException("Inventory not found");

        // Deadlock-safe ordered locking
        InventoryRecord first =
                fromWarehouseId.compareTo(toWarehouseId) < 0 ? from : to;
        InventoryRecord second = (first == from) ? to : from;

        synchronized (first) {
            synchronized (second) {
                from.removeStock(qty);
                to.addStock(qty);
            }
        }
    }
}






