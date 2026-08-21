package com.taskmaster.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public final class AiDtos {

    private AiDtos() {
    }

    @Schema(
            name = "GenerateDescriptionResponse",
            description = """
                    AI-drafted description for a task. This is a suggestion only — it is not saved on \
                    the task; call `PUT /api/tasks/{id}` with the accepted text to keep it.
                    """,
            example = """
                    {
                      "taskId": 1,
                      "title": "Draft wireframes",
                      "generatedDescription": "Complete \\"Draft wireframes\\": outline the steps needed, note any dependencies or blockers, and define what \\"done\\" looks like.",
                      "source": "stub"
                    }
                    """
    )
    public record GenerateDescriptionResponse(
            @Schema(description = "Task id the description was drafted for", example = "1")
            Long taskId,

            @Schema(description = "Task title used as drafting context", example = "Draft wireframes")
            String title,

            @Schema(
                    description = "AI-generated description text — a suggestion, not yet saved on the task",
                    example = "Create mobile and desktop wireframes for the homepage redesign, covering the hero, nav, and footer."
            )
            String generatedDescription,

            @Schema(
                    description = "Where the draft came from: `openai` when a real AI call was made, "
                            + "`stub` when generated locally because no AI key is configured yet",
                    example = "stub",
                    allowableValues = {"openai", "stub"}
            )
            String source
    ) {
    }
}
