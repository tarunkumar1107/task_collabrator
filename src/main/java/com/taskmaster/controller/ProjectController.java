package com.taskmaster.controller;

import com.taskmaster.dto.ProjectDtos.CreateProjectRequest;
import com.taskmaster.dto.ProjectDtos.InviteMemberRequest;
import com.taskmaster.dto.ProjectDtos.MemberResponse;
import com.taskmaster.dto.ProjectDtos.ProjectResponse;
import com.taskmaster.security.SecurityUtils;
import com.taskmaster.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@Tag(name = "Projects", description = "Create projects and manage team membership")
@SecurityRequirement(name = "bearerAuth")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create project", description = "Creates a project and adds the caller as OWNER.")
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
        return projectService.createProject(SecurityUtils.currentUser(), request);
    }

    @GetMapping
    @Operation(summary = "List my projects", description = "Returns all projects where the caller is a member.")
    public List<ProjectResponse> listMine() {
        return projectService.listMyProjects(SecurityUtils.currentUser());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get project", description = "Returns project details if the caller is a member.")
    public ProjectResponse get(@PathVariable Long id) {
        return projectService.getProject(SecurityUtils.currentUser(), id);
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Invite member", description = "Owner-only. Adds an existing user to the project by email.")
    public MemberResponse invite(@PathVariable Long id, @Valid @RequestBody InviteMemberRequest request) {
        return projectService.inviteMember(SecurityUtils.currentUser(), id, request);
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "List members", description = "Lists all members of a project.")
    public List<MemberResponse> members(@PathVariable Long id) {
        return projectService.listMembers(SecurityUtils.currentUser(), id);
    }
}
