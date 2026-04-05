package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.CreateServerInstanceRequest;
import com.filetransfer.onboarding.dto.request.UpdateServerInstanceRequest;
import com.filetransfer.onboarding.dto.response.ServerInstanceResponse;
import com.filetransfer.onboarding.service.ServerInstanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/servers")
@RequiredArgsConstructor
public class ServerInstanceController {

    private final ServerInstanceService service;

    @GetMapping
    public List<ServerInstanceResponse> listAll(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return activeOnly ? service.listActive() : service.listAll();
    }

    @GetMapping("/{id}")
    public ServerInstanceResponse getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    @GetMapping("/by-instance/{instanceId}")
    public ServerInstanceResponse getByInstanceId(@PathVariable String instanceId) {
        return service.getByInstanceId(instanceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServerInstanceResponse create(@Valid @RequestBody CreateServerInstanceRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public ServerInstanceResponse update(@PathVariable UUID id,
                                          @RequestBody UpdateServerInstanceRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
