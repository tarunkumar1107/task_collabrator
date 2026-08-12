package com.taskmaster.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Lifecycle status of a task")
public enum TaskStatus {

    @Schema(description = "Task is created and not yet started")
    OPEN,

    @Schema(description = "Task is actively being worked on")
    IN_PROGRESS,

    @Schema(description = "Task is finished")
    COMPLETED
}
