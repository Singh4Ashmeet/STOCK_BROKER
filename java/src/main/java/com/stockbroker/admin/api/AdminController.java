package com.stockbroker.admin.api;

import com.stockbroker.admin.service.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin REST endpoints.
 */
@RestController
@RequestMapping("/api/v1")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/admin/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminDtos.SystemStats> getSystemStats() {
        return ResponseEntity.ok(adminService.getSystemStats());
    }

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminDtos.ListUsersResponse> listUsers(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok(adminService.listUsers(limit, cursor));
    }

    @GetMapping("/admin/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminDtos.UserAdminView> getUser(@PathVariable String id) {
        return ResponseEntity.ok(adminService.getUser(UUID.fromString(id)));
    }

    @PatchMapping("/admin/users/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminDtos.UserAdminView> updateUserRole(
            @PathVariable String id,
            @RequestBody AdminDtos.UpdateUserRoleRequest request) {
        return ResponseEntity.ok(adminService.updateUserRole(UUID.fromString(id), request));
    }

    @PatchMapping("/admin/users/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminDtos.UserAdminView> updateUserStatus(
            @PathVariable String id,
            @RequestBody AdminDtos.UpdateUserStatusRequest request) {
        return ResponseEntity.ok(adminService.updateUserStatus(UUID.fromString(id), request));
    }
}