package app.hopps.bankimport.service;

import app.hopps.bankimport.parser.PaypalParser;
import app.hopps.shared.bootstrap.TestdataBootstrapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A PayPal account with a fee booking books every imported PayPal fee as a confirmed transaction; rolling the import
 * back removes them again. The fixture holds two fees (one of them refunded, so positive). Alice belongs to Bühnefrei
 * e.V., whose root bommel is 23.
 */
@QuarkusTest
@TestSecurity(user = "alice@example.test", roles = "user")
@OidcSecurity(claims = {
        @Claim(key = "sub", value = "eb4123a3-b722-4798-9af5-8957f823657a")
})
class PaypalFeeBookingTest {

    private static final long ROOT_BOMMEL_ID = 23L;

    @Inject
    Flyway flyway;

    @Inject
    TestdataBootstrapper testdataBootstrapper;

    @Inject
    BankImportWorker worker;

    @BeforeEach
    void setup() {
        flyway.clean();
        flyway.migrate();
        testdataBootstrapper.loadTestdata();
    }

    @Test
    void booksImportedFeesAndRemovesThemOnRollback() throws Exception {
        long groupId = createRequiredGroup();
        long accountId = createAccount(Map.of("bommelId", ROOT_BOMMEL_ID, "area", "IDEELL",
                "categoryValues", Map.of(String.valueOf(groupId), "6855")));

        long importId = importFixture(accountId);

        List<Map<String, Object>> fees = fees(accountId);
        assertEquals(2, fees.size());
        for (Map<String, Object> fee : fees) {
            assertEquals("FULLY_MATCHED", fee.get("status"));
            long transactionId = ((List<Number>) given().get("/bank-transactions/{id}", fee.get("id"))
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("matchedTransactionIds")).get(0).longValue();
            given().get("/transactions/{id}", transactionId)
                    .then()
                    .statusCode(200)
                    .body("status", is("CONFIRMED"))
                    .body("bommelId", is((int) ROOT_BOMMEL_ID))
                    .body("categoryValues[0].value", is("6855"));
        }
        // The payments themselves stay open for matching by hand.
        assertEquals(7, given().get("/bank-transactions/by-account/{id}?status=UNMATCHED", accountId)
                .then()
                .extract()
                .jsonPath()
                .getList("$")
                .size());

        long transactionsBefore = countTransactions();
        given().delete("/imports/{id}", importId).then().statusCode(204);
        assertEquals(transactionsBefore - 2, countTransactions());
    }

    @Test
    void feesStayOpenWithoutFeeBooking() throws Exception {
        long accountId = createAccount(null);
        importFixture(accountId);

        assertEquals(List.of("UNMATCHED", "UNMATCHED"),
                fees(accountId).stream().map(f -> f.get("status")).toList());
    }

    @Test
    void feeBookingCanBeTurnedOff() {
        long groupId = createRequiredGroup();
        long accountId = createAccount(Map.of("bommelId", ROOT_BOMMEL_ID,
                "categoryValues", Map.of(String.valueOf(groupId), "6855")));

        given().contentType(ContentType.JSON)
                .body(Map.of("feeBooking", Map.of()))
                .patch("/bankaccounts/{id}", accountId)
                .then()
                .statusCode(200)
                .body("feeBooking", nullValue());
    }

    @Test
    void rejectsInvalidFeeBooking() {
        long groupId = createRequiredGroup();
        // a value the group does not have
        given().contentType(ContentType.JSON)
                .body(paypalAccount(Map.of("bommelId", ROOT_BOMMEL_ID,
                        "categoryValues", Map.of(String.valueOf(groupId), "9999"))))
                .post("/bankaccounts")
                .then()
                .statusCode(400);
        // a bank account books no fees
        given().contentType(ContentType.JSON)
                .body(Map.of("feeBooking", Map.of("bommelId", ROOT_BOMMEL_ID)))
                .patch("/bankaccounts/{id}", 1L)
                .then()
                .statusCode(400);
    }

    private long createRequiredGroup() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("name", "SKR04-Konto", "required", true, "bommelIds", List.of(ROOT_BOMMEL_ID),
                        "values", List.of("4000", "6855")))
                .post("/category-groups")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    private long createAccount(Map<String, Object> feeBooking) {
        return given().contentType(ContentType.JSON)
                .body(paypalAccount(feeBooking))
                .post("/bankaccounts")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    private static Map<String, Object> paypalAccount(Map<String, Object> feeBooking) {
        Map<String, Object> body = new java.util.HashMap<>(
                Map.of("name", "PayPal", "type", "PAYPAL", "paypalEmail", "kasse@example.test"));
        if (feeBooking != null) {
            body.put("feeBooking", feeBooking);
        }
        return body;
    }

    /** Uploads the fixture and runs the queued import right away instead of waiting for the scheduler. */
    private long importFixture(long accountId) throws IOException, InterruptedException {
        long importId = given().multiPart("file", "export.csv", fixture(), "text/csv")
                .post("/bankaccounts/{id}/imports", accountId)
                .then()
                .statusCode(202)
                .extract()
                .jsonPath()
                .getLong("id");
        worker.pollQueue();
        for (int i = 0; i < 50; i++) {
            String status = given().get("/imports/{id}", importId).then().extract().path("status");
            if ("COMPLETED".equals(status)) {
                return importId;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Import " + importId + " did not complete");
    }

    private List<Map<String, Object>> fees(long accountId) {
        JsonPath body = given().get("/bank-transactions/by-account/{id}", accountId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        return body.<Map<String, Object>> getList("$")
                .stream()
                .filter(t -> PaypalParser.FEE_TYPE.equals(t.get("transactionType")))
                .toList();
    }

    private long countTransactions() {
        return given().get("/transactions/aggregate").then().statusCode(200).extract().jsonPath().getLong("count");
    }

    private static byte[] fixture() throws IOException {
        try (InputStream in = PaypalFeeBookingTest.class.getResourceAsStream("/bankimport/paypal-de-anon.csv")) {
            return in.readAllBytes();
        }
    }
}
