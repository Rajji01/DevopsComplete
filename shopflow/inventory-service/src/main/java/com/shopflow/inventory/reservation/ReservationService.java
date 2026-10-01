package com.shopflow.inventory.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shopflow.inventory.common.InsufficientStockException;
import com.shopflow.inventory.common.ProductNotFoundException;
import com.shopflow.inventory.product.ProductRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final ProductRepository productRepository;
    private final Counter rejectedCounter;

    public ReservationService(ReservationRepository reservationRepository,
                              ProductRepository productRepository,
                              MeterRegistry meterRegistry) {
        this.reservationRepository = reservationRepository;
        this.productRepository = productRepository;
        // business metric, scraped by Prometheus as inventory_reservations_rejected_total
        this.rejectedCounter = Counter.builder("inventory.reservations.rejected")
                .description("Reservations rejected because of insufficient stock")
                .register(meterRegistry);
    }

    /**
     * Idempotent: a second call with the same orderRef returns the existing reservation.
     * Decrement + insert happen in one transaction, so a failed insert rolls the stock back.
     */
    @Transactional
    public Reservation reserve(String orderRef, String sku, int quantity) {
        var existing = reservationRepository.findByOrderRef(orderRef);
        if (existing.isPresent()) {
            log.info("reservation {} already exists, returning it", orderRef);
            return existing.get();
        }

        if (productRepository.decrementStock(sku, quantity) == 0) {
            if (!productRepository.existsBySku(sku)) {
                throw new ProductNotFoundException(sku);
            }
            rejectedCounter.increment();
            throw new InsufficientStockException(sku, quantity);
        }

        log.info("reserved {} x {} for order {}", quantity, sku, orderRef);
        return reservationRepository.save(new Reservation(orderRef, sku, quantity));
    }

    /** Idempotent compensation: releasing twice (or releasing an unknown order) is a no-op. */
    @Transactional
    public void release(String orderRef) {
        reservationRepository.findByOrderRef(orderRef)
                .filter(r -> r.getStatus() == Reservation.Status.RESERVED)
                .ifPresent(r -> {
                    // mark released first: the @Modifying update flushes this change, then clears the context
                    r.release();
                    productRepository.incrementStock(r.getSku(), r.getQuantity());
                    log.info("released {} x {} for order {}", r.getQuantity(), r.getSku(), orderRef);
                });
    }
}
