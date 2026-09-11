package dev.nkap.server.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures the log lines one class emits during a test, so a structured field ({@code MDC})
 * can be asserted on rather than eyeballed in console output — the "asserted, not eyeballed"
 * rule issue #75's plan sets for the reference, the reconciler pass id and the import id.
 *
 * <p>Forces the logger to {@code DEBUG} for the capture's lifetime, restoring whatever level
 * it had on close: a routine line logged at {@code DEBUG} (the reconciler's own per-pass
 * summary, say) must be capturable the same way an operator would see it by turning DEBUG on
 * for this logger, not only the WARN and above a plain surefire run leaves enabled.
 *
 * <p>{@code try}-with-resources releases both: a test that forgot would leave every later
 * test's log lines from the same class flowing into this one's list too, at a level it never
 * asked for.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture(Class<?> loggedClass) {
        this.logger = (Logger) LoggerFactory.getLogger(loggedClass);
        this.previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    /** Every event captured so far, oldest first. */
    public List<ILoggingEvent> events() {
        return appender.list;
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }
}
