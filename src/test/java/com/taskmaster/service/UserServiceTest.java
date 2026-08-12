package com.taskmaster.service;

import com.taskmaster.domain.User;
import com.taskmaster.dto.UserDtos.UpdateProfileRequest;
import com.taskmaster.repository.UserRepository;
import com.taskmaster.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void updateProfile_updatesDisplayNameAndBio() {
        UserPrincipal principal = new UserPrincipal(1L, "alice@example.com", "hash", "Alice");
        User user = new User();
        user.setId(1L);
        user.setEmail("alice@example.com");
        user.setDisplayName("Alice");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = userService.updateProfile(principal, new UpdateProfileRequest("Alice Updated", "Backend engineer"));

        assertThat(response.displayName()).isEqualTo("Alice Updated");
        assertThat(response.bio()).isEqualTo("Backend engineer");
    }
}
