package ru.quipy.payments.logic

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


@Service
class PaymentSystemImpl(
    paymentAccounts: List<PaymentExternalSystemAdapter>
) : PaymentService {
    private val sortedPaymentAccounts = paymentAccounts.sortedBy { it.price }

    companion object {
        val logger = LoggerFactory.getLogger(PaymentSystemImpl::class.java)
    }

    override fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                return
            }

            val result = sortedPaymentAccounts.any { it.performPaymentAsync(paymentId, amount, paymentStartedAt, deadline) }
            if (result) {
                break
            }

            Thread.sleep(10)
        }
    }
}