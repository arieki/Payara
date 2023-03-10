package fish.payara.samples.otel;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class SpanBean {

    private static final Logger LOG = Logger.getLogger(SpanBean.class.getName());

    @WithSpan
    public String span() {
        LOG.log(Level.INFO, "invoking span");
        return "this is span without value";
    }

    @WithSpan("spanName")
    public String spanName() {
        LOG.log(Level.INFO, "invoking spanName");
        return "this is span with value";
    }

    @WithSpan(kind = SpanKind.SERVER)
    public String spanKind() {
        LOG.log(Level.INFO, "invoking spanKind");
        return "this is span with kind";
    }

    @WithSpan
    public String spanArgs(@SpanAttribute(value = "arg") String arg) {
        LOG.log(Level.INFO, "invoking spanArgs with spanAttribute {0}", arg);
        return "this is span with attribute " + arg;
    }
}
