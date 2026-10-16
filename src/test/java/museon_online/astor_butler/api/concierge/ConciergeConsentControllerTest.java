package museon_online.astor_butler.api.concierge;

import museon_online.astor_butler.domain.consent.ConsentVaultService;
import museon_online.astor_butler.domain.identity.IdentityService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One consent for both bots: the Concierge shares, reads and withdraws it by the Telegram user id. */
class ConciergeConsentControllerTest {

    private final ConsentVaultService consents = mock(ConsentVaultService.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final ConciergeConsentController controller = new ConciergeConsentController(consents, identity);

    @Test
    void aSharedConsentGrantsItAndRemembersTheContact() {
        var response = controller.share(new ConciergeConsentController.ShareRequest(555L, "+79120000060", "Анна"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("granted", true);
        verify(identity).rememberConciergeContact(555L, "Анна", "+79120000060");
        verify(consents).grantPrivacyPolicyFromConcierge(555L);
    }

    @Test
    void withoutAUserIdOrAPhoneNothingIsGranted() {
        for (var bad : java.util.List.of(new ConciergeConsentController.ShareRequest(null, "+79120000060", null),
                new ConciergeConsentController.ShareRequest(555L, null, null),
                new ConciergeConsentController.ShareRequest(555L, "8 912 000", null))) {
            assertThat(controller.share(bad).getStatusCode().value()).isEqualTo(400);
        }
        verify(consents, never()).grantPrivacyPolicyFromConcierge(anyLong());
    }

    @Test
    void readingGivesTheContactOnlyWithConsent() {
        when(consents.hasGrantedPrivacyPolicy(555L)).thenReturn(true);
        when(identity.primaryPhone(555L)).thenReturn(Optional.of("+79120000060"));
        when(identity.firstName(555L)).thenReturn(Optional.of("Анна"));
        when(consents.hasGrantedPrivacyPolicy(556L)).thenReturn(false);

        assertThat(controller.read(555L).getBody()).containsEntry("granted", true).containsEntry("phone", "+79120000060")
                .containsEntry("firstName", "Анна");
        assertThat(controller.read(556L).getBody()).containsEntry("granted", false).containsEntry("phone", null)
                .containsEntry("firstName", null);
        verify(identity, never()).primaryPhone(556L);
    }

    @Test
    void withdrawingRevokesTheConsent() {
        assertThat(controller.withdraw(555L).getBody()).containsEntry("granted", false);
        verify(consents).revokePrivacyPolicy(555L);
    }
}
