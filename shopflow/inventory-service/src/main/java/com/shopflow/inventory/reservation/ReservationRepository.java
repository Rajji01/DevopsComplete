package com.shopflow.inventory.reservation;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByOrderRef(String orderRef);

    /**
     * Atomic status transition, same pattern as ProductRepository.decrementStock: the WHERE
     * clause re-checks the current status under the row lock, so of two concurrent releases
     * exactly one gets 1 back and the other 0. Without this (no @Version on Reservation),
     * both would read RESERVED and both would put the stock back.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Reservation r set r.status = :to where r.orderRef = :orderRef and r.status = :from")
    int transition(@Param("orderRef") String orderRef,
                   @Param("from") Reservation.Status from,
                   @Param("to") Reservation.Status to);
}
