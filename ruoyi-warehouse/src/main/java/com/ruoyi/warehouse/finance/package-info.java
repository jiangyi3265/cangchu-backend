/**
 * Native financial workflows using RuoYi authentication, explicit client/warehouse
 * scopes, optimistic record versions and actor-scoped idempotency requests.
 * Pricing rules share wh_record with order pricing. Private evidence tables never
 * appear in general record DTOs. Invoices freeze line-level half-even VAT and keep
 * historical lines after unconfirmation; active claims alone are released.
 * Order-count tiers freeze their rule on the first despatch in a UTC period.
 * Recurring and order-tier settlement require completed, whole UTC periods.
 * Historical storage, credit-note/adjustment workflows, external invoice sending
 * and legacy financial migration are deliberately unavailable here.
 */
package com.ruoyi.warehouse.finance;
