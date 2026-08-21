package com.taskmaster.service;

/**
 * Abstraction over the AI provider used to draft task descriptions, so callers (and tests)
 * don't depend on the concrete HTTP/provider integration.
 */
public interface LlmClient {

    /**
     * Drafts a task description from its title and any existing notes.
     *
     * @param title        the task title (required context for the draft)
     * @param existingNotes current description/notes, if any, used as extra context
     * @return the generated description text
     */
    String generateTaskDescription(String title, String existingNotes);
}
