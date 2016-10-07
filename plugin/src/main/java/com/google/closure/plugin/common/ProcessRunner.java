package com.google.closure.plugin.common;

import java.util.concurrent.Future;

import org.apache.maven.plugin.logging.Log;

/**
 * Abstracts away running a process so that steps that need to run other
 * processes can be tested.
 */
public interface ProcessRunner {

  /**
   * @param logPrefix a short string that can be prefixed to stdout and stderr
   *     from the process written to the log
   * @return a promise for the exit code.
   */
  Future<Integer> run(
      Log log, String logPrefix, Iterable<? extends String> argv,
      OutputReceiver outputReceiver);

  /**
   * Receives process output from STDOUT and STDERR one line at a time.
   * This makes it easier to process log messages and pass them through to
   * {@link org.sonatype.plexus.build.incremental.BuildContext#addMessage}
   */
  public interface OutputReceiver {
    /** Receives messages on the process output pipes. */
    void processLine(String line);

    /** Called when all output has been processed. */
    void allProcessed();
  }
}
