package com.vacancytracker.web;

import com.vacancytracker.vacancy.DashboardService;
import com.vacancytracker.vacancy.RepliesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    private final RepliesService replies;

    public DashboardController(DashboardService dashboard, RepliesService replies) {
        this.dashboard = dashboard;
        this.replies = replies;
    }

    @GetMapping
    public DashboardService.Dashboard get() {
        return dashboard.dashboard();
    }

    @GetMapping("/replies")
    public RepliesService.Replies replies(@RequestParam(defaultValue = "30") int days) {
        try {
            return replies.replies(days);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
