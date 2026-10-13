package com.stockbroker.admin.service;

import com.stockbroker.admin.api.AdminDtos;
import com.stockbroker.auth.domain.User;
import com.stockbroker.auth.repo.UserRepository;
import com.stockbroker.common.error.BusinessProblem;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin service.
 */
@Service
@Transactional
public class AdminService {

    private final UserRepository userRepository;

    public AdminService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AdminDtos.SystemStats getSystemStats() {
        // TODO: Implement actual stats from various repositories
        return new AdminDtos.SystemStats(
                0, 0, 0, "0", true, 0, 0
        );
    }

    public AdminDtos.ListUsersResponse listUsers(int limit, String cursor) {
        List<User> users = userRepository.findAll(PageRequest.of(0, limit)).getContent();
        List<AdminDtos.UserAdminView> responses = users.stream()
                .map(this::toUserView)
                .collect(Collectors.toList());
        return new AdminDtos.ListUsersResponse(responses, null);
    }

    public AdminDtos.UserAdminView getUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("User not found",
                        java.util.Map.of("code", "USER_NOT_FOUND")));
        return toUserView(user);
    }

    public AdminDtos.UserAdminView updateUserRole(UUID userId, AdminDtos.UpdateUserRoleRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("User not found",
                        java.util.Map.of("code", "USER_NOT_FOUND")));

        if (!"USER".equals(request.role()) && !"ADMIN".equals(request.role())) {
            throw new BusinessProblem.ValidationFailed("Invalid role",
                    java.util.Map.of("code", "INVALID_ROLE"));
        }

        user.setRole(request.role());
        userRepository.save(user);
        return toUserView(user);
    }

    public AdminDtos.UserAdminView updateUserStatus(UUID userId, AdminDtos.UpdateUserStatusRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("User not found",
                        java.util.Map.of("code", "USER_NOT_FOUND")));

        if (!"ACTIVE".equals(request.status()) && !"LOCKED".equals(request.status()) && !"DISABLED".equals(request.status())) {
            throw new BusinessProblem.ValidationFailed("Invalid status",
                    java.util.Map.of("code", "INVALID_STATUS"));
        }

        user.setStatus(request.status());
        userRepository.save(user);
        return toUserView(user);
    }

    private AdminDtos.UserAdminView toUserView(User user) {
        return new AdminDtos.UserAdminView(
                user.getId().toString(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.getCreatedAt().toString(),
                null // last_login not tracked yet
        );
    }
}