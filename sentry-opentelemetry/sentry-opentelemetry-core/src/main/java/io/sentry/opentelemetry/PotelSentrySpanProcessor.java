package io.sentry.opentelemetry;

import static io.sentry.opentelemetry.InternalSemanticAttributes.IS_REMOTE_PARENT;
import static io.sentry.opentelemetry.SentryOtelKeys.SENTRY_SCOPES_KEY;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.sentry.HubAdapter;
import io.sentry.IHub;
import io.sentry.Scopes;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PotelSentrySpanProcessor implements SpanProcessor {
  private final @NotNull SentryWeakSpanStorage spanStorage = SentryWeakSpanStorage.getInstance();
  private final @NotNull IHub hub;

  public PotelSentrySpanProcessor() {
    this(HubAdapter.getInstance());
  }

  PotelSentrySpanProcessor(final @NotNull IHub hub) {
    this.hub = hub;
  }

  @Override
  public void onStart(final @NotNull Context parentContext, final @NotNull ReadWriteSpan otelSpan) {
    if (!ensurePrerequisites(otelSpan)) {
      return;
    }

    final @Nullable Span parentSpan = Span.fromContextOrNull(parentContext);
    if (parentSpan != null) {
      otelSpan.setAttribute(IS_REMOTE_PARENT, parentSpan.getSpanContext().isRemote());
    }

    final @Nullable Scopes scopesFromContext = parentContext.get(SENTRY_SCOPES_KEY);
    final @NotNull Scopes scopes =
        scopesFromContext != null
            ? scopesFromContext.forkedCurrentScope("spanprocessor")
            : Scopes.forkedRoots("spanprocessor");
    final @NotNull SpanContext spanContext = otelSpan.getSpanContext();
    spanStorage.storeScopes(spanContext, scopes);

    // TODO this should go away after hubs/scopes merge
    spanStorage.storeHub(spanContext, Sentry.getCurrentHub());
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(final @NotNull ReadableSpan spanBeingEnded) {
    System.out.println("span ended: " + spanBeingEnded.getSpanContext().getSpanId());
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }

  private boolean ensurePrerequisites(final @NotNull ReadableSpan otelSpan) {
    if (!hasSentryBeenInitialized()) {
      hub.getOptions()
          .getLogger()
          .log(
              SentryLevel.DEBUG,
              "Not forwarding OpenTelemetry span to Sentry as Sentry has not yet been initialized.");
      return false;
    }

    final @NotNull SpanContext otelSpanContext = otelSpan.getSpanContext();
    if (!otelSpanContext.isValid()) {
      hub.getOptions()
          .getLogger()
          .log(
              SentryLevel.DEBUG,
              "Not forwarding OpenTelemetry span to Sentry as the span is invalid.");
      return false;
    }

    return true;
  }

  private boolean hasSentryBeenInitialized() {
    return hub.isEnabled();
  }
}
