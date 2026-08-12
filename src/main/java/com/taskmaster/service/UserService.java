package com.taskmaster.service;

import com.taskmaster.domain.User;
import com.taskmaster.dto.UserDtos.UpdateProfileRequest;
import com.taskmaster.dto.UserDtos.UserResponse;
import com.taskmaster.exception.ApiException;
import com.taskmaster.repository.UserRepository;
import com.taskmaster.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(UserPrincipal principal) {
        return UserResponse.from(requireUser(principal.getId()));
    }

    @Transactional
    public UserResponse updateProfile(UserPrincipal principal, UpdateProfileRequest request) {
        User user = requireUser(principal.getId());
        user.setDisplayName(request.displayName().trim());
        user.setBio(request.bio());
        return UserResponse.from(userRepository.save(user));
    }

    public User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
