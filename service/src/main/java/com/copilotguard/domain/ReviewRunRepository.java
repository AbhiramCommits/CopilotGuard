package com.copilotguard.domain;

import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReviewRunRepository extends JpaRepository<ReviewRun, Long> {

    @Query("select coalesce(avg(r.costUsd), 0) from ReviewRun r")
    BigDecimal averageCostUsd();
}
