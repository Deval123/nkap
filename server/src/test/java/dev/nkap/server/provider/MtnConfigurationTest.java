package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.server.payment.PaymentRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

/**
 * What the MTN wiring is <strong>not</strong> allowed to need.
 *
 * <p>Until issue #67, {@code query} was handed only a reference, so the facade found the
 * product by reading the operation off the payment the gateway had recorded — a lookup this
 * configuration injected from the payment store. The contract now carries the capability
 * (ADR 0008) and the lookup is gone. This test is what keeps it gone: the composition root
 * does not get to reintroduce "the adapter asks the gateway what it is translating" quietly,
 * because nothing it builds can see a payment at all.
 */
class MtnConfigurationTest {

    private static List<Method> beanMethods() {
        return Arrays.stream(MtnConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .toList();
    }

    @Test
    @DisplayName("nothing MtnConfiguration builds is handed the payment store — the #62 lookup cannot grow back")
    void no_bean_here_can_see_a_payment() {
        assertThat(beanMethods()).as("the beans to check").isNotEmpty();

        for (Method bean : beanMethods()) {
            assertThat(bean.getParameterTypes())
                    .as("%s takes no PaymentRepository — an unused parameter is the lookup "
                            + "growing back, so this checks the signature, not the body", bean.getName())
                    .doesNotContain(PaymentRepository.class);
        }
    }

    @Test
    @DisplayName("the MTN adapter bean is built from configuration alone")
    void the_adapter_bean_is_built_from_properties_alone() {
        Method adapterBean = beanMethods().stream()
                .filter(method -> method.getName().equals("mtnAdapter"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("MtnConfiguration no longer declares an mtnAdapter bean"));

        assertThat(adapterBean.getParameterTypes())
                .as("MtnProperties and nothing else")
                .containsExactly(MtnProperties.class);
    }
}
