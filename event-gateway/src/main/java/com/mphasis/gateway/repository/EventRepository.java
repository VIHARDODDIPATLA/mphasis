package com.assessment.gateway.repository;

import com.assessment.gateway.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, String> {
    List<Event> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
