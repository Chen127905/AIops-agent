package com.cc.opsagent.integration.web;

import com.cc.opsagent.integration.application.ManagedServiceService;
import com.cc.opsagent.integration.domain.ManagedService;
import com.cc.opsagent.simulator.application.OpsDataProvider.HealthSnapshot;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/managed-services")
public class ManagedServiceController {
    private final ManagedServiceService services;
    public ManagedServiceController(ManagedServiceService services) { this.services = services; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<ManagedService> list() { return services.list(); }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ManagedService create(@Valid @RequestBody ManagedServiceRequest request) {
        return services.create(request.toDraft());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ManagedService update(@PathVariable long id, @Valid @RequestBody ManagedServiceRequest request) {
        return services.update(id, request.toDraft());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { services.delete(id); }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public HealthSnapshot test(@PathVariable long id) { return services.test(id); }
}
