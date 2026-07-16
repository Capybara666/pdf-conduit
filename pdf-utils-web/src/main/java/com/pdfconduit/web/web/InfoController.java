package com.pdfconduit.web.web;

import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.web.dto.OperationInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Non-operation endpoints: liveness and the operation catalog the UI can render from. */
@RestController
@RequestMapping("/api")
public class InfoController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/operations")
    public List<OperationInfo> operations() {
        return Arrays.stream(OperationType.values()).map(OperationInfo::of).toList();
    }
}
