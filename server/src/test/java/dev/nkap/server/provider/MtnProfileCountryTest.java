package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.mtn.MtnProfile;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MtnProperties.Installation}'s country and the provider id derived from it are one field
 * with a rule, not two with a chance to disagree. {@link MtnConfiguration} strips and lower-cases
 * the country once, for the id and the routing. This test holds the profiles to the same value, so
 * a padded {@code " cm"} cannot again register as {@code mtn-cm} while its profile carries
 * {@code " cm"}.
 *
 * <p>Nothing else pinned the stripping, so this is the drift guard for the two profile builders.
 * They are private, and reached here by reflection rather than widened for a test.
 */
class MtnProfileCountryTest {

    private static MtnProperties.Installation padded(String country) {
        return new MtnProperties.Installation(URI.create("https://x.test"), "sandbox", "sub-key", "api-user",
                "api-key", Currency.XAF, country, Duration.ofSeconds(20),
                new MtnProperties.Disbursement("disb-sub-key", "disb-user", "disb-key"));
    }

    private static Object invoke(String method, MtnProperties.Installation installation) throws Exception {
        Method m = MtnConfiguration.class.getDeclaredMethod(method, MtnProperties.Installation.class);
        m.setAccessible(true);
        return m.invoke(null, installation);
    }

    @Test
    @DisplayName("a padded country builds both profiles with the country the provider id names, not the padded one")
    void a_padded_country_gives_profiles_the_country_the_id_names() throws Exception {
        for (String country : List.of(" cm", "cm ", " CM\t", "cm\n")) {
            MtnProperties.Installation installation = padded(country);
            String idCountry = ((ProviderId) invoke("providerId", installation)).toString().substring("mtn-".length());

            assertThat(idCountry).as("the id, for %s", country.replace("\n", "\\n")).isEqualTo("cm");
            assertThat(((MtnProfile) invoke("collectionProfile", installation)).country())
                    .as("the collections profile, for %s", country.replace("\n", "\\n")).isEqualTo(idCountry);
            assertThat(((MtnProfile) invoke("disbursementProfile", installation)).country())
                    .as("the disbursements profile, for %s", country.replace("\n", "\\n")).isEqualTo(idCountry);
        }
    }
}
