package com.cybertown.controller;

import com.cybertown.service.PlayerIdentityService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/player")
public class GuestSessionController {
    private final PlayerIdentityService identities;

    public GuestSessionController(PlayerIdentityService identities) { this.identities = identities; }

    @PostMapping("/guest")
    public PlayerIdentityService.GuestSession issueGuest() {
        return identities.issueGuest();
    }
}
