package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.function.Consumer;
import java.io.IOException;
import java.util.List;

public interface PathwayContext {
  String PROPAGATION_KEY = "dd-pathway-ctx";

  boolean isStarted();

  void setCheckpoint(List<String> tags, Consumer<StatsPoint> pointConsumer);

  byte[] encode() throws IOException;

  String strEncode() throws IOException;
}
