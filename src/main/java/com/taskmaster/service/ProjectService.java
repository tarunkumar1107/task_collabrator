package com.taskmaster.service;

import com.taskmaster.domain.Project;
import com.taskmaster.domain.ProjectMember;
import com.taskmaster.domain.ProjectRole;
import com.taskmaster.domain.User;
import com.taskmaster.dto.ProjectDtos.CreateProjectRequest;
import com.taskmaster.dto.ProjectDtos.InviteMemberRequest;
import com.taskmaster.dto.ProjectDtos.MemberResponse;
import com.taskmaster.dto.ProjectDtos.ProjectResponse;
import com.taskmaster.exception.ApiException;
import com.taskmaster.repository.ProjectMemberRepository;
import com.taskmaster.repository.ProjectRepository;
import com.taskmaster.repository.UserRepository;
import com.taskmaster.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            UserRepository userRepository
    ) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ProjectResponse createProject(UserPrincipal principal, CreateProjectRequest request) {
        User owner = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        Project project = new Project();
        project.setName(request.name().trim());
        project.setDescription(request.description());
        project.setOwner(owner);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(owner);
        membership.setRole(ProjectRole.OWNER);
        projectMemberRepository.save(membership);

        return ProjectResponse.from(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listMyProjects(UserPrincipal principal) {
        return projectRepository.findAllByMemberUserId(principal.getId()).stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(UserPrincipal principal, Long projectId) {
        Project project = requireMembership(projectId, principal.getId());
        return ProjectResponse.from(project);
    }

    @Transactional
    public MemberResponse inviteMember(UserPrincipal principal, Long projectId, InviteMemberRequest request) {
        Project project = requireOwner(projectId, principal.getId());
        User invitee = userRepository.findByEmailIgnoreCase(request.email().trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User with that email does not exist"));

        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, invitee.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "User is already a project member");
        }

        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(invitee);
        member.setRole(ProjectRole.MEMBER);
        return MemberResponse.from(projectMemberRepository.save(member));
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(UserPrincipal principal, Long projectId) {
        requireMembership(projectId, principal.getId());
        return projectMemberRepository.findByProjectId(projectId).stream()
                .map(MemberResponse::from)
                .toList();
    }

    public Project requireMembership(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not a member of this project");
        }
        return project;
    }

    public Project requireOwner(Long projectId, Long userId) {
        Project project = requireMembership(projectId, userId);
        if (!project.getOwner().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the project owner can perform this action");
        }
        return project;
    }
}
