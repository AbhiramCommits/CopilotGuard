package com.copilotguard.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface ReviewRunRepository extends JpaRepository<ReviewRun, Long> {

    @Query("select coalesce(avg(r.costUsd), 0) from ReviewRun r")
    BigDecimal averageCostUsd();
}
