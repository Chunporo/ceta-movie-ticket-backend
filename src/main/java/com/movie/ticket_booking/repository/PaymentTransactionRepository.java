package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    /** Idempotency flag (D-09): exactly one caller sees 1 for a given txn_ref. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PaymentTransaction p set p.processedAt = :now "
            + "where p.provider = :provider and p.txnRef = :txnRef and p.processedAt is null")
    int markProcessed(@Param("provider") String provider, @Param("txnRef") String txnRef, @Param("now") Instant now);

    Optional<PaymentTransaction> findByProviderAndTxnRef(String provider, String txnRef);
}
