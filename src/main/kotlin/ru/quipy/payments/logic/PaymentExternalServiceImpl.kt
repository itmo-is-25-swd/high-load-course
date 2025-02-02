package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.FixedWindowRateLimiter
import ru.quipy.common.utils.NonBlockingOngoingWindow
import ru.quipy.common.utils.OngoingWindow
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: ExternalServiceProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val window = NonBlockingOngoingWindow(parallelRequests)
    private val rateLimiter = FixedWindowRateLimiter(rateLimitPerSec, 1, TimeUnit.SECONDS)

    private val client = OkHttpClient.Builder()
        .readTimeout(requestAverageProcessingTime.toMillis() * 5, TimeUnit.MILLISECONDS)
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long): Boolean {
        if (!isEnabled(deadline)) {
            return false
        }

        val transactionId = createTransaction(paymentId, paymentStartedAt)

        return executeRequest(transactionId, paymentId, amount)
    }

    private fun executeRequest(transactionId: UUID, paymentId: UUID, amount: Int): Boolean {
        val body = try {
            val responseBody = executeRequestSafeWithRetries(transactionId, paymentId, amount)
            logSuccess(transactionId, paymentId, responseBody)

            responseBody
        } catch (e: Exception) {
            when (e) {
                is SocketTimeoutException -> {
                    logSocketTimeout(transactionId, paymentId, e)
                }

                else -> {
                    logUnknownError(transactionId, paymentId, e)
                }
            }

            ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
        }

        return body.result
    }

    private fun logSuccess(
        transactionId: UUID,
        paymentId: UUID,
        body: ExternalSysResponse
    ) {
        logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

        // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
        // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
        paymentESService.update(paymentId) {
            it.logProcessing(body.result, now(), transactionId, reason = body.message)
        }
    }

    private fun logUnknownError(transactionId: UUID, paymentId: UUID, e: Exception) {
        logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

        paymentESService.update(paymentId) {
            it.logProcessing(false, now(), transactionId, reason = e.message)
        }
    }

    private fun logSocketTimeout(transactionId: UUID, paymentId: UUID, e: Exception) {
        logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
        paymentESService.update(paymentId) {
            it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
        }
    }

    private fun createTransaction(paymentId: UUID, paymentStartedAt: Long): UUID {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()
        logger.info("[$accountName] Submit for $paymentId , txId: $transactionId")

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }
        return transactionId
    }

    private fun executeRequestSafeWithRetries(
        transactionId: UUID,
        paymentId: UUID,
        amount: Int,
    ): ExternalSysResponse {
        return try {
            executeHttpRequest(transactionId, paymentId, amount)
        } finally {
            window.releaseWindow()
        }
    }

    private fun executeHttpRequest(
        transactionId: UUID,
        paymentId: UUID,
        amount: Int
    ): ExternalSysResponse {
        val request = Request.Builder().run {
            url("http://localhost:1234/external/process?serviceName=${serviceName}&accountName=${accountName}&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
            post(emptyBody)
        }.build()

        return client.newCall(request).execute().use { response ->
            try {
                mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
            } catch (e: Exception) {
                logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
            }
        }
    }

    override val price: Int = properties.price

    override fun isEnabled(deadline: Long): Boolean {
        if (deadline <= System.currentTimeMillis() + requestAverageProcessingTime.toMillis()) {
            return false
        }

        val windowResult = window.putIntoWindow()

        if (windowResult is NonBlockingOngoingWindow.WindowResponse.Fail) {
            return false
        }

        return if (rateLimiter.tick()) {
            true
        } else {
            window.releaseWindow()
            false
        }
    }

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()