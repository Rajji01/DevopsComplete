package com.shopflow.ledger;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shopflow.ledger.domain.Account;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByCode(String code);
}
