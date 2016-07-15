package com.google.common.html.plugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Checks that a bundle of outputs from separate compiles don't step on each
 * others toes.
 */
public final class OutputAmbiguityChecker {

  /** An output from a compile run with diagnostic info. */
  public static final class Output {
    /** Human readable. */
    public final String description;
    /** The output that should be disjoint with others. */
    public final File outputFile;
    /** ctor */
    public Output(String description, File outputFile) {
      this.description = description;
      this.outputFile = outputFile;
    }
  }

  /**
   * @throws MojoExecutionException if unambiguous.
   */
  public static void requireOutputsUnambiguous(
      Log log, Iterable<? extends Output> outputs)
  throws MojoExecutionException {
    Map<File, Output> perFile = Maps.newHashMap();
    Set<File> ambiguous = Sets.newLinkedHashSet();
    for (Output o : outputs) {
      File canonOutputFile;
      try {
        canonOutputFile = o.outputFile.getCanonicalFile();
      } catch (IOException ex) {
        log.error("Bad output file " + o.outputFile, ex);
        ambiguous.add(o.outputFile);
        continue;
      }
      Output old = perFile.put(canonOutputFile, o);
      if (old != null) {
        ambiguous.add(o.outputFile);

        StringBuilder message = new StringBuilder();
        message.append("Output file conflict: ").append(o.outputFile);
        if (o.description.equals(old.description)) {
          message.append(" is used twice as ").append(o.description);
        } else {
          message.append(" is used as ").append(old.description)
              .append(" and as ").append(o.description);
        }
        message.append('.');
        if (!o.outputFile.equals(old.outputFile)) {
          message.append("  Both ").append(old.outputFile).append(" and ")
              .append(o.outputFile).append(" map to ").append(canonOutputFile);
        }
        log.error(message);
      }
    }
    if (!ambiguous.isEmpty()) {
      throw new MojoExecutionException("Ambiguous CSS outputs: " + ambiguous);
    }
  }

}
