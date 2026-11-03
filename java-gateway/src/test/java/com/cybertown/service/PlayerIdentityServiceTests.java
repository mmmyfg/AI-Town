package com.cybertown.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerIdentityServiceTests {
    @Test
    void signedGuestTokenIdentifiesOnlyItsOwner() {
        PlayerIdentityService identities = new PlayerIdentityService(true, "test-secret-for-unit-tests", 3600);
        PlayerIdentityService.GuestSession guest = identities.issueGuest();

        assertThat(identities.authenticate("Bearer " + guest.token())).isEqualTo(guest.player_id());
        assertThatThrownBy(() -> identities.authenticate(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> identities.verify(guest.token() + "tampered"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        PlayerIdentityService identities = new PlayerIdentityService(true, "test-secret-for-unit-tests", -1);
        assertThatThrownBy(() -> identities.verify(identities.issueGuest().token()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
