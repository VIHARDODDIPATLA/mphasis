package com.assessment.account.repository;

import com.assessment.account.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    List<Transaction> findByAccountIdOrderByEventTimestampAsc(String accountId);

    @Query("SELECT SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) FROM Transaction t WHERE t.accountId = :accountId")
    BigDecimal calculateBalance(@Param("accountId") String accountId);

    long countByAccountId(String accountId);
}
