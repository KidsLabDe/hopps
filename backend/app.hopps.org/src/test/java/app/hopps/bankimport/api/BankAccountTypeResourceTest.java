package app.hopps.bankimport.api;

import app.hopps.shared.bootstrap.TestdataBootstrapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * A bank account is identified by its IBAN, a PayPal account by its PayPal e-mail address instead, and each takes only
 * its own kind of import file. Alice's organization owns bank account 1.
 */
@QuarkusTest
@TestSecurity(user = "alice@example.test", roles = "user")
@OidcSecurity(claims = {
        @Claim(key = "sub", value = "eb4123a3-b722-4798-9af5-8957f823657a")
})
class BankAccountTypeResourceTest {

    private static final long BANK_ACCOUNT_ID = 1L;
    private static final String MT940 = """
            :20:STARTUMS
            :25:10050000/12345678
            :28C:00001/001
            :60F:C250602EUR100,00
            :61:2506020602CR59,99NTRFNONREF
            :86:?20Spende
            :62F:C250602EUR159,99
            -
            """;

    @Inject
    Flyway flyway;

    @Inject
    TestdataBootstrapper testdataBootstrapper;

    @BeforeEach
    void setup() {
        flyway.clean();
        flyway.migrate();
        testdataBootstrapper.loadTestdata();
    }

    @Test
    void paypalAccountNeedsNoIban() {
        createAccount("{\"name\":\"PayPal\",\"type\":\"PAYPAL\",\"paypalEmail\":\"Kasse@Example.test\"}")
                .statusCode(201)
                .body("type", is("PAYPAL"))
                .body("iban", nullValue())
                .body("paypalEmail", is("kasse@example.test"))
                .body("currency", is("EUR"));
    }

    @Test
    void accountsAreBankAccountsByDefault() {
        createAccount("{\"name\":\"Giro\",\"iban\":\"DE21500105171234567890\"}")
                .statusCode(201)
                .body("type", is("BANK"));
    }

    @Test
    void eachTypeNeedsItsOwnIdentifier() {
        createAccount("{\"name\":\"PayPal\",\"type\":\"PAYPAL\"}").statusCode(400);
        createAccount("{\"name\":\"PayPal\",\"type\":\"PAYPAL\",\"paypalEmail\":\"kasse@example.test\","
                + "\"iban\":\"DE21500105171234567890\"}").statusCode(400);
        createAccount("{\"name\":\"Giro\",\"type\":\"BANK\"}").statusCode(400);
        createAccount("{\"name\":\"PayPal\",\"type\":\"PAYPAL\",\"paypalEmail\":\"no-address\"}").statusCode(400);
    }

    @Test
    void paypalEmailIsUniqueRegardlessOfCase() {
        createAccount("{\"name\":\"PayPal\",\"type\":\"PAYPAL\",\"paypalEmail\":\"kasse@example.test\"}")
                .statusCode(201);
        createAccount("{\"name\":\"PayPal 2\",\"type\":\"PAYPAL\",\"paypalEmail\":\"KASSE@example.test\"}")
                .statusCode(400);
    }

    @Test
    void bankAccountGetsNoPaypalEmail() {
        given().contentType(ContentType.JSON)
                .body("{\"paypalEmail\":\"kasse@example.test\"}")
                .patch("/bankaccounts/{id}", BANK_ACCOUNT_ID)
                .then()
                .statusCode(400);
    }

    @Test
    void importFilesGoOnlyIntoTheirKindOfAccount() throws IOException {
        long paypalAccountId = createAccount(
                "{\"name\":\"PayPal\",\"type\":\"PAYPAL\",\"paypalEmail\":\"kasse@example.test\"}")
                        .statusCode(201)
                        .extract()
                        .jsonPath()
                        .getLong("id");
        byte[] paypalExport = paypalFixture();

        upload(BANK_ACCOUNT_ID, paypalExport).statusCode(400);
        upload(paypalAccountId, MT940.getBytes(StandardCharsets.UTF_8)).statusCode(400);
        upload(paypalAccountId, paypalExport).statusCode(202);
    }

    private static byte[] paypalFixture() throws IOException {
        try (InputStream in = BankAccountTypeResourceTest.class
                .getResourceAsStream("/bankimport/paypal-de-anon.csv")) {
            return in.readAllBytes();
        }
    }

    private ValidatableResponse createAccount(String body) {
        return given().contentType(ContentType.JSON).body(body).post("/bankaccounts").then();
    }

    private ValidatableResponse upload(long accountId, byte[] content) {
        return given().multiPart("file", "export.csv", content, "text/csv")
                .post("/bankaccounts/{id}/imports", accountId)
                .then();
    }
}
