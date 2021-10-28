package datadog.trace.bootstrap.instrumentation.ci.utils;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CIUtils {

  private CIUtils() {}

  /**
   * Search the path that contains the the target file. If the current path does not have the target
   * file, the method continues with the parent path. If the path is not found, it returns null.
   *
   * @param current
   * @param target
   * @return the path that contains the target file.
   */
  public static Path findPathBackwards(
      final Path current, final String target, final boolean isTargetDirectory) {
    if (current == null || target == null || target.isEmpty()) {
      return null;
    }

    final Path targetPath = current.resolve(target);
    if (Files.exists(targetPath)) {
      if (isTargetDirectory && Files.isDirectory(targetPath)) {
        return current;
      } else if (!isTargetDirectory && Files.isRegularFile(targetPath)) {
        return current;
      } else {
        return null;
      }
    } else {
      return findPathBackwards(current.getParent(), target, isTargetDirectory);
    }
  }
}
