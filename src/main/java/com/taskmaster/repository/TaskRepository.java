package com.taskmaster.repository;

import com.taskmaster.domain.Task;
import com.taskmaster.domain.TaskStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByAssigneeId(Long assigneeId, Sort sort);

    @Query("""
            select t from Task t
            where t.project.id = :projectId
              and (:status is null or t.status = :status)
              and (
                    :q is null
                    or lower(t.title) like lower(concat('%', :q, '%'))
                    or lower(coalesce(t.description, '')) like lower(concat('%', :q, '%'))
                  )
            """)
    List<Task> searchInProject(
            @Param("projectId") Long projectId,
            @Param("status") TaskStatus status,
            @Param("q") String q,
            Sort sort
    );
}
