package dev.nkap.simulator.scenario;

/**
 * Pairs a {@link RequestMatcher} with the scenario to play when it matches. Rules are
 * consulted in order and the first match wins.
 *
 * @param <T> the face's scenario
 */
public interface Rule<T> {

    RequestMatcher match();

    T scenario();
}
