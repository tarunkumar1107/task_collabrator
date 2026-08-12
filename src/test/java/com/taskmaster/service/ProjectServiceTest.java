package com.taskmaster.service;

import com.taskmaster.domain.Project;
import com.taskmaster.domain.ProjectMember;
import com.taskmaster.domain.ProjectRole;
import com.taskmaster.domain.User;
import com.taskmaster.dto.ProjectDtos.CreateProjectRequest;
import com.taskmaster.dto.ProjectDtos.InviteMemberRequest;
import com.taskmaster.exception.ApiException;
import com.taskmaster.repository.ProjectMemberRepository;
import com.taskmaster.repository.ProjectRepository;
import com.taskmaster.repository.UserRepository;
import com.taskmaster.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ProjectService projectService;

    private UserPrincipal principal;
    private User owner;

    @BeforeEach
    void setUp() {
        principal = new UserPrincipal(1L, "alice@example.com", "hash", "Alice");
        owner = new User();
        owner.setId(1L);
        owner.setEmail("alice@example.com");
    }

    @Test
    void createProject_addsOwnerMembership() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(10L);
            return project;
        });
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = projectService.createProject(principal, new CreateProjectRequest("Alpha", "Demo project"));

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("Alpha");

        ArgumentCaptor<ProjectMember> captor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(ProjectRole.OWNER);
    }

    @Test
    void inviteMember_addsRegisteredUser() {
        Project project = new Project();
        project.setId(10L);
        project.setOwner(owner);
        project.setName("Alpha");

        User invitee = new User();
        invitee.setId(2L);
        invitee.setEmail("bob@example.com");
        invitee.setDisplayName("Bob");

        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));
        when(projectMemberRepository.existsByProjectIdAndUserId(10L, 1L)).thenReturn(true);
        when(userRepository.findByEmailIgnoreCase("bob@example.com")).thenReturn(Optional.of(invitee));
        when(projectMemberRepository.existsByProjectIdAndUserId(10L, 2L)).thenReturn(false);
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(invocation -> {
            ProjectMember member = invocation.getArgument(0);
            member.setId(5L);
            return member;
        });

        var response = projectService.inviteMember(principal, 10L, new InviteMemberRequest("bob@example.com"));

        assertThat(response.userId()).isEqualTo(2L);
        assertThat(response.role()).isEqualTo(ProjectRole.MEMBER);
    }

    @Test
    void inviteMember_nonOwner_forbidden() {
        User otherOwner = new User();
        otherOwner.setId(9L);
        Project project = new Project();
        project.setId(10L);
        project.setOwner(otherOwner);

        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));
        when(projectMemberRepository.existsByProjectIdAndUserId(10L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> projectService.inviteMember(
                principal, 10L, new InviteMemberRequest("bob@example.com")
        ))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
