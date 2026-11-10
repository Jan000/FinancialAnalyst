package net.jandev.financialanalyst.data.banking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// Feldnamen laut https://enablebanking.com/docs/api/reference/ – unbekannte Felder werden ignoriert.

@Serializable
data class AspspList(val aspsps: List<Aspsp> = emptyList())

@Serializable
data class Aspsp(
    val name: String,
    val country: String,
    @SerialName("maximum_consent_validity") val maximumConsentValidity: Long = 0,
    @SerialName("psu_types") val psuTypes: List<String> = emptyList(),
    val beta: Boolean = false,
)

@Serializable
data class AspspRef(val name: String, val country: String)

@Serializable
data class Access(@SerialName("valid_until") val validUntil: String)

@Serializable
data class StartAuthRequest(
    val access: Access,
    val aspsp: AspspRef,
    val state: String,
    @SerialName("redirect_url") val redirectUrl: String,
    @SerialName("psu_type") val psuType: String = "personal",
    val language: String = "de",
)

@Serializable
data class StartAuthResponse(val url: String, @SerialName("authorization_id") val authorizationId: String? = null)

@Serializable
data class CreateSessionRequest(val code: String)

@Serializable
data class SessionResponse(
    @SerialName("session_id") val sessionId: String,
    val accounts: List<BankAccount> = emptyList(),
    val aspsp: AspspRef? = null,
    val access: Access? = null,
)

@Serializable
data class AccountIdentification(val iban: String? = null)

@Serializable
data class BankAccount(
    val uid: String? = null,
    @SerialName("account_id") val accountId: AccountIdentification? = null,
    val name: String? = null,
    val details: String? = null,
    val product: String? = null,
    val currency: String? = null,
    @SerialName("cash_account_type") val cashAccountType: String? = null,
    @SerialName("identification_hash") val identificationHash: String? = null,
)

@Serializable
data class SessionStatus(val status: String? = null, val access: Access? = null)

@Serializable
data class Amount(val currency: String = "EUR", val amount: String = "0")

@Serializable
data class BalanceList(val balances: List<Balance> = emptyList())

@Serializable
data class Balance(
    @SerialName("balance_amount") val amount: Amount,
    @SerialName("balance_type") val type: String = "",
)

@Serializable
data class Party(val name: String? = null)

@Serializable
data class TransactionPage(
    val transactions: List<BankTransaction> = emptyList(),
    @SerialName("continuation_key") val continuationKey: String? = null,
)

@Serializable
data class BankTransaction(
    @SerialName("entry_reference") val entryReference: String? = null,
    @SerialName("transaction_amount") val amount: Amount,
    @SerialName("credit_debit_indicator") val creditDebit: String = "DBIT",
    val status: String? = null,
    @SerialName("booking_date") val bookingDate: String? = null,
    @SerialName("value_date") val valueDate: String? = null,
    @SerialName("transaction_date") val transactionDate: String? = null,
    val creditor: Party? = null,
    val debtor: Party? = null,
    @SerialName("remittance_information") val remittanceInformation: List<String>? = null,
    val note: String? = null,
)

@Serializable
data class ApiError(val message: String? = null, val code: Int? = null, val error: String? = null)

class BankApiException(val errorCode: String?, val httpStatus: Int, message: String) : Exception(message) {
    /** Die Freigabe bei der Bank ist abgelaufen oder widerrufen; der Nutzer muss neu verbinden. */
    val needsReconnect: Boolean
        get() = errorCode in setOf("EXPIRED_SESSION", "REVOKED_SESSION", "CLOSED_SESSION", "SESSION_DOES_NOT_EXIST", "WRONG_SESSION_STATUS")
    val rateLimited: Boolean get() = httpStatus == 429 || errorCode == "ASPSP_RATE_LIMIT_EXCEEDED"
}

/** Schlanker Client für die Kontoinformations-API von Enable Banking. */
class EnableBankingApi(
    private val tokenProvider: () -> String,
    private val baseUrl: String = "https://api.enablebanking.com",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        // Felder mit Standardwert (psu_type, language) müssen in der Anfrage stehen.
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }
    private val jsonMedia = "application/json".toMediaType()

    private suspend inline fun <reified T> call(
        path: String,
        query: Map<String, String?> = emptyMap(),
        method: String = "GET",
        body: String? = null,
        noinline inspect: ((String) -> Unit)? = null,
    ): T = withContext(Dispatchers.IO) {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${tokenProvider()}")
            .method(method, body?.toRequestBody(jsonMedia))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val error = runCatching { json.decodeFromString<ApiError>(text) }.getOrNull()
                throw BankApiException(error?.error, response.code, describe(error?.error, response.code, error?.message))
            }
            inspect?.invoke(text)
            json.decodeFromString<T>(text)
        }
    }

    private fun describe(code: String?, status: Int, message: String?): String = when {
        code in setOf("EXPIRED_SESSION", "REVOKED_SESSION", "CLOSED_SESSION", "SESSION_DOES_NOT_EXIST") ->
            "Die Freigabe bei der Bank ist abgelaufen. Bitte die Bank neu verbinden."
        status == 429 || code == "ASPSP_RATE_LIMIT_EXCEEDED" ->
            "Die Bank lässt heute keine weiteren automatischen Abrufe zu. Bitte in einigen Stunden erneut versuchen."
        code == "REDIRECT_URI_NOT_ALLOWED" ->
            "Die Rücksprung-Adresse ist bei Enable Banking nicht für diese Anwendung eingetragen."
        code == "NO_ACCOUNTS_ADDED" ->
            "Für diese Anwendung sind bei Enable Banking noch keine Konten verknüpft („Activate by linking accounts“)."
        code == "ASPSP_ERROR" || code == "ASPSP_TIMEOUT" ->
            "Die Bank hat gerade nicht geantwortet. Bitte später erneut versuchen."
        status == 401 || status == 403 ->
            "Enable Banking hat den Zugang abgelehnt. Bitte App-ID und Schlüsseldatei prüfen."
        else -> "Fehler beim Bankabruf (${code ?: status})${message?.let { ": $it" }.orEmpty()}"
    }

    suspend fun banks(country: String): List<Aspsp> =
        call<AspspList>("/aspsps", mapOf("country" to country, "psu_type" to "personal")).aspsps

    suspend fun startAuth(request: StartAuthRequest): StartAuthResponse =
        call("/auth", method = "POST", body = json.encodeToString(StartAuthRequest.serializer(), request))

    suspend fun createSession(code: String): SessionResponse =
        call("/sessions", method = "POST", body = json.encodeToString(CreateSessionRequest.serializer(), CreateSessionRequest(code)))

    suspend fun session(sessionId: String): SessionStatus = call("/sessions/$sessionId")

    suspend fun deleteSession(sessionId: String) {
        call<ApiError>("/sessions/$sessionId", method = "DELETE")
    }

    /** Stammdaten eines Kontos; `name` ist der Kontoinhaber. */
    suspend fun accountDetails(accountUid: String): BankAccount = call("/accounts/$accountUid/details")

    suspend fun balances(accountUid: String): List<Balance> = call<BalanceList>("/accounts/$accountUid/balances").balances

    /** Lädt alle Seiten. [longest] holt beim ersten Abruf so viel Historie, wie die Bank hergibt. */
    suspend fun transactions(
        accountUid: String,
        /** Ohne Angabe und mit [longest] liefert die Bank alles, was sie hergibt. */
        dateFrom: String?,
        longest: Boolean,
        /** Bekommt jede Antwortseite unverändert, für die Diagnose der gelieferten Felder. */
        inspect: ((String) -> Unit)? = null,
    ): List<BankTransaction> {
        val all = ArrayList<BankTransaction>()
        var key: String? = null
        var pages = 0
        do {
            val page = call<TransactionPage>(
                "/accounts/$accountUid/transactions",
                mapOf(
                    "date_from" to dateFrom,
                    "strategy" to if (longest) "longest" else null,
                    "continuation_key" to key,
                ),
                inspect = inspect,
            )
            all.addAll(page.transactions)
            key = page.continuationKey
            pages++
        } while (key != null && pages < 200)
        return all
    }
}
