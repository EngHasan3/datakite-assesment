package com.datakite.ledger.aspect;

import com.datakite.ledger.dto.TransferRequest;
import com.datakite.ledger.service.TransferExecutionService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Fraud-prevention business rule: any transfer over the review threshold is
 * forcefully diverted to PENDING_REVIEW instead of moving money.
 *
 * The pointcut targets TransferExecutionService.executeTransfer(..), which
 * TransferService only ever reaches through this aspect's Spring proxy (an
 * injected cross-bean call, not this.executeTransfer(...) from within the
 * same class) - self-invocation would silently bypass this advice and the
 * $5,000 rule would never fire.
 */
@Aspect
@Component
public class FraudDetectionAspect {

    private final TransferExecutionService transferExecutionService;
    private final BigDecimal reviewThreshold;

    public FraudDetectionAspect(TransferExecutionService transferExecutionService,
                                 @Value("${app.fraud.review-threshold}") BigDecimal reviewThreshold) {
        this.transferExecutionService = transferExecutionService;
        this.reviewThreshold = reviewThreshold;
    }

    @Around("execution(* com.datakite.ledger.service.TransferExecutionService.executeTransfer(..)) && args(request, idempotencyKey)")
    public Object interceptHighValueTransfers(ProceedingJoinPoint pjp, TransferRequest request, String idempotencyKey) throws Throwable {
        if (request.amount().compareTo(reviewThreshold) > 0) {
            return transferExecutionService.flagForReview(request, idempotencyKey);
        }
        return pjp.proceed();
    }
}
