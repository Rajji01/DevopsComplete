package com.shopflow.ledger;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shopflow.ledger.domain.Posting;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    List<Posting> findByJournalEntryId(Long journalEntryId);

    List<Posting> findByAccountId(Long accountId);
}
