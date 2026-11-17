package com.ruoyi.warehouse.billing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Recorded legacy results; Maven does not need Node or invoke the old runtime. */
class BillingGoldenTest {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @TestFactory
    Stream<DynamicTest> nativeJavaMatchesLegacySyntheticCases() throws Exception {
        Map<String, Object> fixture;
        try (InputStream stream = getClass().getResourceAsStream("/billing/legacy-golden.json")) {
            assertNotNull(stream, "Committed synthetic oracle is required");
            fixture = JSON.readValue(stream, new TypeReference<>() { });
        }
        assertEquals(Boolean.TRUE, fixture.get("synthetic"));
        return BillingCalculator.rows(fixture.get("cases")).stream().map(test -> DynamicTest.dynamicTest(
            String.valueOf(test.get("id")), () -> {
                Map<String, Object> input = BillingCalculator.object(test.get("input"));
                String before = JSON.writeValueAsString(input);
                if (test.containsKey("error")) {
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> calculate(test));
                    assertEquals(test.get("error"), failure.getMessage());
                } else assertEquivalent(test.get("expected"), calculate(test), String.valueOf(test.get("id")));
                assertEquals(before, JSON.writeValueAsString(input), "Calculator must not mutate caller snapshots");
            }));
    }

    private static Object calculate(Map<String, Object> test) {
        Map<String, Object> input = BillingCalculator.object(test.get("input"));
        return switch (String.valueOf(test.get("method"))) {
            case "decimal" -> BillingCalculator.toRaw(BillingCalculator.decimal(input.get("value")));
            case "moneyRound" -> BillingCalculator.moneyRound(BillingCalculator.fromRaw(String.valueOf(input.get("raw"))));
            case "productVolume" -> BillingCalculator.productVolume(BillingCalculator.object(input.get("product")), Boolean.TRUE.equals(input.get("carton")));
            case "tierTotal" -> BillingCalculator.tierTotal(((Number) input.get("quantity")).longValue(), BillingCalculator.rows(input.get("tiers")), Boolean.TRUE.equals(input.get("split")), input.get("minimum"));
            case "pickingQuote" -> BillingCalculator.pickingQuote(input);
            case "orderQuote" -> BillingCalculator.orderQuote(input);
            case "goodsInQuote" -> BillingCalculator.goodsInQuote(input);
            case "storageQuote" -> BillingCalculator.storageQuote(input);
            case "courierQuote" -> BillingCalculator.courierQuote(input);
            default -> throw new IllegalArgumentException("Unknown oracle method");
        };
    }

    private static void assertEquivalent(Object expected, Object actual, String path) {
        if (expected instanceof Number left && actual instanceof Number right) {
            assertEquals(0, new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())), path);
        } else if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
            assertEquals(left.keySet(), right.keySet(), path + " keys");
            for (Object key : left.keySet()) assertEquivalent(left.get(key), right.get(key), path + "." + key);
        } else if (expected instanceof List<?> left && actual instanceof List<?> right) {
            assertEquals(left.size(), right.size(), path + " size");
            for (int i = 0; i < left.size(); i++) assertEquivalent(left.get(i), right.get(i), path + "[" + i + "]");
        } else assertEquals(expected, actual, path);
    }
}
