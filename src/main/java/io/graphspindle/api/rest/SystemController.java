package io.graphspindle.api.rest;

import io.graphspindle.admin.SystemInfoService;
import io.graphspindle.admin.SystemInfoService.SystemInfo;
import io.graphspindle.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "System")
public class SystemController {

    private final SystemInfoService info;

    public SystemController(SystemInfoService info) {
        this.info = info;
    }

    @GetMapping("/api/v1/system/info")
    @Operation(summary = "Version, active LLM provider/model/host, vocabulary profile and the data-transfer notice")
    public SystemInfo info() {
        return info.info(CurrentActor.require());
    }
}
