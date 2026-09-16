package com.copilotguard.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewCommentRepository extends JpaRepository<ReviewComment, Long> {

    long countByHumanVerdict(HumanVerdict humanVerdict);

    @Query("select c.category, c.severity, count(c) from ReviewComment c "
            + "where c.humanVerdict = :verdict group by c.category, c.severity")
    List<Object[]> countRejectedByCategoryAndSeverity(@Param("verdict") HumanVerdict verdict);
}
