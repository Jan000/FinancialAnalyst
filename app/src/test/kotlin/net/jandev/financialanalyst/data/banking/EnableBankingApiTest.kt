package net.jandev.financialanalyst.data.banking

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Prüft den Client gegen aufgezeichnete Beispielantworten aus der API-Dokumentation. */
class EnableBankingApiTest {
    private val server = MockWebServer()
    private lateinit var api: EnableBankingApi

    @Before
    fun setUp() {
        server.start()
        api = EnableBankingApi(tokenProvider = { "test-token" }, baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse(code = code, body = body))
    }

    @Test
    fun listsBanksAndSendsBearerToken() = runTest {
        enqueue("""{"aspsps":[{"name":"Nordea","country":"FI","beta":false,"maximum_consent_validity":15552000,"psu_types":["business"],"logo":"x","auth_methods":[]}]}""")
        val banks = api.banks("FI")
        assertEquals(listOf(Aspsp("Nordea", "FI", 15552000, listOf("business"), false)), banks)
        val request = server.takeRequest()
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertEquals("FI", request.url.queryParameter("country"))
    }

    @Test
    fun startsAuthorizationWithDocumentedBody() = runTest {
        enqueue("""{"url":"https://auth.enablebanking.com/ais/start?sessionid=abc","authorization_id":"abc"}""")
        val response = api.startAuth(
            StartAuthRequest(Access("2026-12-01T12:00:00Z"), AspspRef("DKB", "DE"), "state-1", "finanzanalyst://callback"),
        )
        assertEquals("https://auth.enablebanking.com/ais/start?sessionid=abc", response.url)
        val body = server.takeRequest().body!!.utf8()
        for (expected in listOf(
            "\"valid_until\":\"2026-12-01T12:00:00Z\"", "\"name\":\"DKB\"", "\"country\":\"DE\"",
            "\"state\":\"state-1\"", "\"redirect_url\":\"finanzanalyst://callback\"", "\"psu_type\":\"personal\"",
        )) {
            assertTrue("fehlt: $expected in $body", expected in body)
        }
    }

    @Test
    fun readsSessionAccounts() = runTest {
        enqueue(
            """{"session_id":"s1","accounts":[{"account_id":{"iban":"FI0455231152453547"},"uid":"07cc67f4","cash_account_type":"CACC",
               "currency":"EUR","name":"Girokonto","identification_hash":"h1","all_account_ids":[],"usage":"PRIV"}],
               "aspsp":{"name":"Nordea","country":"FI"},"psu_type":"personal","access":{"valid_until":"2026-12-01T12:00:00.000000+00:00"}}""",
        )
        val session = api.createSession("code-1")
        assertEquals("s1", session.sessionId)
        val account = session.accounts.single()
        assertEquals("07cc67f4", account.uid)
        assertEquals("FI0455231152453547", account.accountId?.iban)
        assertEquals("h1", account.identificationHash)
        assertEquals("""{"code":"code-1"}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun followsContinuationKeysEvenAcrossEmptyPages() = runTest {
        enqueue(
            """{"transactions":[{"entry_reference":"e1","transaction_amount":{"currency":"EUR","amount":"12.34"},
               "credit_debit_indicator":"DBIT","status":"BOOK","booking_date":"2026-11-03","creditor":{"name":"REWE"},
               "remittance_information":["Einkauf","Danke"]}],"continuation_key":"k1"}""",
        )
        enqueue("""{"transactions":[],"continuation_key":"k2"}""")
        enqueue(
            """{"transactions":[{"transaction_amount":{"currency":"EUR","amount":"2500.00"},"credit_debit_indicator":"CRDT",
               "status":"BOOK","value_date":"2026-10-30","debtor":{"name":"Beispiel AG"}}],"continuation_key":null}""",
        )
        val all = api.transactions("uid-1", "2026-10-01", longest = true)
        assertEquals(2, all.size)
        assertEquals("e1", all[0].entryReference)
        assertEquals("12.34", all[0].amount.amount)
        assertEquals(listOf("Einkauf", "Danke"), all[0].remittanceInformation)
        assertEquals("Beispiel AG", all[1].debtor?.name)

        val first = server.takeRequest()
        assertEquals("/accounts/uid-1/transactions", first.url.encodedPath)
        assertEquals("2026-10-01", first.url.queryParameter("date_from"))
        assertEquals("longest", first.url.queryParameter("strategy"))
        assertEquals(null, first.url.queryParameter("continuation_key"))
        assertEquals("k1", server.takeRequest().url.queryParameter("continuation_key"))
        assertEquals("k2", server.takeRequest().url.queryParameter("continuation_key"))
    }

    @Test
    fun readsBalances() = runTest {
        enqueue("""{"balances":[{"name":"Booked balance","balance_amount":{"currency":"EUR","amount":"1.23"},"balance_type":"CLBD","reference_date":"2026-11-09"}]}""")
        assertEquals(listOf(Balance(Amount("EUR", "1.23"), "CLBD")), api.balances("uid-1"))
    }

    @Test
    fun expiredSessionAsksForReconnect() = runTest {
        enqueue("""{"message":"Session is expired","code":401,"error":"EXPIRED_SESSION"}""", code = 401)
        try {
            api.balances("uid-1")
            fail("Fehler erwartet")
        } catch (e: BankApiException) {
            assertTrue(e.needsReconnect)
            assertEquals("EXPIRED_SESSION", e.errorCode)
        }
    }

    @Test
    fun rateLimitIsRecognised() = runTest {
        enqueue("""{"message":"Too many","code":429,"error":"ASPSP_RATE_LIMIT_EXCEEDED"}""", code = 429)
        try {
            api.transactions("uid-1", "2026-10-01", longest = false)
            fail("Fehler erwartet")
        } catch (e: BankApiException) {
            assertTrue(e.rateLimited)
            assertTrue(!e.needsReconnect)
        }
    }
}
