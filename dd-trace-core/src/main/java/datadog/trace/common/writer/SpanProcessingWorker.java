package datadog.trace.common.writer;

import static datadog.trace.util.AgentThreadFactory.AgentThread.SPAN_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

public class SpanProcessingWorker implements AutoCloseable {

  private final Thread samplingThread;
  private final SamplingHandler samplingHandler;
  private final MpscBlockingConsumerArrayQueue<Object> droppedTracesQueue;
  private final Queue<Object> sampledSpansQueue;
  private final SingleSpanSampler singleSpanSampler;

  public static SpanProcessingWorker build(
      int capacity, Queue<Object> sampledSpansQueue, SingleSpanSampler singleSpanSampler) {
    if (singleSpanSampler == null) {
      return null;
    }
    return new SpanProcessingWorker(capacity, sampledSpansQueue, singleSpanSampler);
  }

  private SpanProcessingWorker(
      int capacity, Queue<Object> sampledSpansQueue, SingleSpanSampler singleSpanSampler) {
    this.samplingHandler = new SamplingHandler();
    this.samplingThread = newAgentThread(SPAN_PROCESSOR, samplingHandler);
    this.droppedTracesQueue = new MpscBlockingConsumerArrayQueue<>(capacity);
    this.sampledSpansQueue = sampledSpansQueue;
    this.singleSpanSampler = singleSpanSampler;
  }

  public void start() {
    this.samplingThread.start();
  }

  @Override
  public void close() {
    samplingThread.interrupt();
    try {
      samplingThread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (InterruptedException ignored) {
    }
  }

  public <T extends CoreSpan<T>> boolean publish(List<T> trace) {
    return droppedTracesQueue.offer(trace);
  }

  public Queue<Object> getDroppedTracesQueue() {
    return droppedTracesQueue;
  }

  private final class SamplingHandler implements Runnable, MessagePassingQueue.Consumer<Object> {

    @Override
    public void run() {
      try {
        runDutyCycle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void runDutyCycle() throws InterruptedException {
      Thread thread = Thread.currentThread();
      while (!thread.isInterrupted()) {
        consumeFromInputQueue();
      }
    }

    private void consumeFromInputQueue() throws InterruptedException {
      Object event = droppedTracesQueue.poll(100, MILLISECONDS);
      if (null != event) {
        onEvent(event);
        consumeBatch(droppedTracesQueue);
      }
    }

    @SuppressWarnings("unchecked")
    public void onEvent(Object event) {
      if (event instanceof List) {
        List<DDSpan> trace = (List<DDSpan>) event;
        ArrayList<DDSpan> sampledSpans = new ArrayList<>(trace.size());
        for (DDSpan span : trace) {
          if (singleSpanSampler.setSamplingPriority(span)) {
            sampledSpans.add(span);
          } // else ignore dropped spans
        }
        if (sampledSpans.size() > 0 && !sampledSpansQueue.offer(sampledSpans)) {
          // TODO should decrement sent traces and increment dropped traces/spans counters (see
          // datadog.trace.common.writer.RemoteWriter.write)
        }
      }
    }

    private void consumeBatch(MessagePassingQueue<Object> queue) {
      queue.drain(this, queue.size());
    }

    @Override
    public void accept(Object event) {
      onEvent(event);
    }
  }
}
