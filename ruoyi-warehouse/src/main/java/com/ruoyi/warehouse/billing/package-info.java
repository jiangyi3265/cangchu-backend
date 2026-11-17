/**
 * Native Java pricing, independent of persistence and transport.
 *
 * <p>Application services supply validated, authorized domain snapshots using the
 * legacy field names. Jackson callers should deserialize fractional JSON numbers
 * as BigDecimal. Prices, quantities, raw calculations, and rounding use BigDecimal;
 * decimal's scientific-notation input branch alone emulates the legacy parser's
 * binary expansion for compatibility. Amounts have currency units; raw strings are
 * integer billionths. Never pass a raw string to moneyRound without fromRaw.</p>
 *
 * <p>pickingQuote/orderQuote require client, order, products; optional overrides,
 * channelTiers, excludePicking, reworkComponents. goodsInQuote requires client,
 * asn, products, received and optional complete; the application must persist the
 * returned billedSkus/fixedChargesBilled atomically with receiving. storageQuote
 * requires client, stock, products, locations, with locationPrices/history and
 * frequency/from/to where applicable. Historical peaks require actual snapshots.
 * courierQuote requires a preselected authorized price rule, weight and optional
 * parcels. This layer never selects a carrier account or calls a carrier.</p>
 *
 * <p>orderTierAdjustment takes the count of eligible commercial orders, not unit
 * quantities, plus already posted charges; the service must exclude split-child
 * and billing-excluded orders, scope the period/client/channel, lock the charging
 * group, and persist charges/idempotency in one transaction. recurringAmount and
 * BillingPeriods calculate only; they do not post invoices. ShipmentWeightCalculator
 * consumes historical fulfillment lines and verified packing/material evidence;
 * the service remains responsible for validating that evidence. No method changes
 * stock, journals, invoices, external platforms, or its input snapshots.</p>
 */
package com.ruoyi.warehouse.billing;
