package com.shopflow.inventory.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    public record ReserveRequest(@NotBlank String orderRef,
                                 @NotBlank String sku,
                                 @Min(1) @Max(100) int quantity) {
    }

    public record ReservationResponse(String orderRef, String sku, int quantity, Reservation.Status status) {

        static ReservationResponse from(Reservation r) {
            return new ReservationResponse(r.getOrderRef(), r.getSku(), r.getQuantity(), r.getStatus());
        }
    }

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@Valid @RequestBody ReserveRequest request) {
        return ReservationResponse.from(
                reservationService.reserve(request.orderRef(), request.sku(), request.quantity()));
    }

    @DeleteMapping("/{orderRef}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable String orderRef) {
        reservationService.release(orderRef);
    }
}
