package com.taskmaster.repository;

import com.taskmaster.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("""
            select p from Project p
            join ProjectMember pm on pm.project = p
            where pm.user.id = :userId
            order by p.createdAt desc
            """)
    List<Project> findAllByMemberUserId(@Param("userId") Long userId);
}
