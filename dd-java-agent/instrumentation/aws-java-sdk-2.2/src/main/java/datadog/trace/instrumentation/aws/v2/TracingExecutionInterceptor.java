package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.aws.v2.AwsSdkClientDecorator.AWS_LEGACY_TRACING;
import static datadog.trace.instrumentation.aws.v2.AwsSdkClientDecorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;

/** AWS request execution interceptor */
public class TracingExecutionInterceptor implements ExecutionInterceptor {

  public static final ExecutionAttribute<AgentSpan> SPAN_ATTRIBUTE =
      new ExecutionAttribute<>("DatadogSpan");

  private static final Logger log = LoggerFactory.getLogger(TracingExecutionInterceptor.class);

  private final ContextStore<SdkResponse, String> contextStore;

  public TracingExecutionInterceptor(ContextStore<SdkResponse, String> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public void beforeExecution(
      final Context.BeforeExecution context, final ExecutionAttributes executionAttributes) {
    if (!AWS_LEGACY_TRACING && isPollingRequest(context.request())) {
      return; // SQS messages spans are created by aws-java-sqs-2.0
    }

    final AgentSpan span = startSpan(DECORATE.spanName(executionAttributes));
    DECORATE.afterStart(span);
    executionAttributes.putAttribute(SPAN_ATTRIBUTE, span);
  }

  @Override
  public void afterMarshalling(
      final Context.AfterMarshalling context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    if (span != null) {
      DECORATE.onRequest(span, context.httpRequest());
      DECORATE.onSdkRequest(span, context.request());
      DECORATE.onAttributes(span, executionAttributes);
    }
  }

  @Override
  public SdkHttpRequest modifyHttpRequest(
      Context.ModifyHttpRequest context, ExecutionAttributes executionAttributes) {
    if (Config.get().isAwsPropagationEnabled()) {
      try {
        final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
        if (span != null) {
          SdkHttpRequest.Builder requestBuilder = context.httpRequest().toBuilder();
          propagate().inject(span, requestBuilder, DECORATE, TracePropagationStyle.XRAY);
          return requestBuilder.build();
        }
      } catch (Throwable e) {
        log.warn("Unable to inject trace header", e);
      }
    }
    return context.httpRequest();
  }

  @Override
  public void beforeTransmission(
      final Context.BeforeTransmission context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span;
    if (!AWS_LEGACY_TRACING && isPollingRequest(context.request())) {
      // SQS messages spans are created by aws-java-sqs-2.0 - replace client scope with no-op,
      // so we can tell when receive call is complete without affecting the rest of the trace
      span = noopSpan();
    } else {
      span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    }
    if (span != null) {
      // This scope will be closed by AwsHttpClientInstrumentation since ExecutionInterceptor API
      // doesn't provide a way to run code in same thread after transmission has been scheduled.
      activateSpan(span);
    }
  }

  @Override
  public void afterExecution(
      final Context.AfterExecution context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    if (span != null) {
      executionAttributes.putAttribute(SPAN_ATTRIBUTE, null);
      // Call onResponse on both types of responses:
      DECORATE.onResponse(span, context.response());
      DECORATE.onResponse(span, context.httpResponse());
      DECORATE.beforeFinish(span);
      span.finish();
    }
    if (!AWS_LEGACY_TRACING && isPollingRequest(context.request())) {
      // store queueUrl inside response for SqsReceiveResultInstrumentation
      context
          .request()
          .getValueForField("QueueUrl", String.class)
          .ifPresent(queueUrl -> contextStore.put(context.response(), queueUrl));
    }
  }

  @Override
  public void onExecutionFailure(
      final Context.FailedExecution context, final ExecutionAttributes executionAttributes) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    if (span != null) {
      executionAttributes.putAttribute(SPAN_ATTRIBUTE, null);
      DECORATE.onError(span, context.exception());
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  private static boolean isPollingRequest(SdkRequest request) {
    return null != request
        && "software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest"
            .equals(request.getClass().getName());
  }

  public static void muzzleCheck() {
    // Noop
  }
}
