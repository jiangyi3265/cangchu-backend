/**
 * Native ordinary manual orders. Create/update and every action require a stable
 * actor-scoped requestId. Reuse the original payload after an uncertain response;
 * a different payload cannot reuse the key. HTTP errors outside the domain error
 * contract must not be interpreted as proof that a transaction did not commit.
 *
 * <p>Orders are server-marked JAVA_MANUAL_V1/MANUAL/Manual. Client source/status,
 * allocations, proof, charge and provider fields are never accepted. NEW orders
 * can be held/released; allocation reserves exact source layers, picking confirms
 * eligibility, cancellation releases reservations, and manual despatch consumes
 * them. Per-action row locks and the same SQL transaction cover request evidence,
 * order status, inventory, layer quantities, reservation/stock ledger, audit and
 * a single charge with a private immutable pricing snapshot. FIFO uses original
 * source received_at. Transfers never manufacture a fresh receipt date.</p>
 *
 * <p>This module deliberately blocks virtual/infinite products, mandatory scan or
 * rework and imported/provider/advanced workflows. Period billing by order count
 * registers one commercial order in the finance period in the same transaction;
 * only packaging/administration extras are posted immediately. The period workflow
 * subsequently settles picking by order count using its frozen rule snapshot.
 * Native single-order pricing and channel tiers by units are supported. Manual
 * tracking is a user supplied reference only: no carrier label, carrier purchase,
 * platform synchronization or external acknowledgement is implied. Cancel remains
 * available for releasing intact reservations when a client is subsequently stopped.
 * Apply backend/sql/warehouse_003_orders.sql and warehouse_005_finance.sql after
 * warehouse_001.sql before use.</p>
 */
package com.ruoyi.warehouse.orders;
